package dev.materione.core.document;

import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.LosslessYamlDocument;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The filesystem as a document store (master §9.4): structured reads and
 * atomic writes over {@code content/packs/}, with per-resource revision
 * numbers so stale editor writes are rejected instead of silently clobbering.
 *
 * <p>Files stay canonical (D1) — every write goes through the lossless YAML
 * layer's syntax gate, and the bytes the pipeline later parses are exactly
 * the bytes written here. Revision history lives in
 * {@code storage/revisions/<pack>/<path>.jsonl} (durable, operator-backed-up):
 * one JSON line per change, capped, each carrying the resulting content hash.
 * When a file changes <em>outside</em> the editor (hand-edited YAML, git
 * pull), the next read detects the hash drift and journals an
 * {@code external} revision — which is what makes conflict detection honest.
 */
public final class DocumentStore {

    /** Pack-relative directories that hold editable documents (§7.2) — derived from the one layout source so new content types are writable by construction. */
    public static final List<String> TYPES =
            dev.materione.core.pipeline.PackLayout.CONTENT_DIRS;

    private static final int JOURNAL_CAP = 200;

    private final Path packsRoot;
    private final Path revisionsDir;
    private final Object lock = new Object();

    public DocumentStore(Path packsRoot, Path revisionsDir) {
        this.packsRoot = Objects.requireNonNull(packsRoot, "packsRoot");
        this.revisionsDir = Objects.requireNonNull(revisionsDir, "revisionsDir");
    }

    // ------------------------------------------------------------------
    // records

    public record PackSummary(String id) {}

    /** One keyed resource inside a file: {@code path} is pack-relative. */
    public record ResourceRef(String pack, String type, String path, String resourceId) {}

    /** One current canonical document root, read without synchronizing revisions. */
    public record ResourceDocument(
            ResourceRef resource, LosslessYamlDocument document, String readError) {}

    /** Mutable read budget used only by bounded authoring projections. */
    public interface SummaryReadBudget {
        long remainingBytes();

        void consume(long bytes);

        /** Fails the projection when its remaining capacity is exhausted. */
        void requireRemainingCapacity();
    }

    public record Document(String pack, String path, String raw, int revision, String sha256) {}

    public record Revision(int rev, long timestampMs, String actor, String action, String sha256) {}

    /** Exact owner-file and journal bytes captured for a composite transaction rollback. */
    public record Checkpoint(
            String pack,
            String path,
            boolean documentExisted,
            byte[] documentBytes,
            boolean journalExisted,
            byte[] journalBytes) {}

    // ------------------------------------------------------------------
    // browsing

    /** Packs under {@code content/packs/} — those with a readable pack.yml. */
    public List<PackSummary> listPacks() {
        List<PackSummary> packs = new ArrayList<>();
        if (!Files.isDirectory(packsRoot)) {
            return packs;
        }
        try (Stream<Path> dirs = Files.list(packsRoot)) {
            dirs.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve("pack.yml")))
                    .map(dir -> dir.getFileName().toString())
                    .sorted()
                    .forEach(id -> packs.add(new PackSummary(id)));
        } catch (IOException e) {
            // fail-soft: an unreadable packs root browses as empty (D6)
        }
        return packs;
    }

    /**
     * Keyed resources of one pack (or all packs when {@code packId} is
     * null), optionally filtered by type directory and a case-insensitive
     * substring query against {@code pack:type/id}.
     */
    public List<ResourceRef> listResources(String packId, String typeFilter, String query) {
        List<ResourceRef> refs = new ArrayList<>();
        List<String> packs = packId == null
                ? listPacks().stream().map(PackSummary::id).toList()
                : List.of(packId);
        for (String pack : packs) {
            Path packDir = packsRoot.resolve(pack);
            for (String type : TYPES) {
                if (typeFilter != null && !type.equals(typeFilter.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Path typeDir = packDir.resolve(type);
                if (!Files.isDirectory(typeDir)) {
                    continue;
                }
                collectRefs(pack, type, typeDir, "", query, refs);
            }
        }
        return refs;
    }

    /**
     * Reads current canonical document roots in one inventory pass without
     * touching the revision journal. Symlinked pack, type, and document paths
     * are excluded so a browse projection never follows an external path.
     */
    public List<ResourceDocument> listResourceDocuments(String packId, String typeFilter) {
        List<ResourceDocument> resources = new ArrayList<>();
        forEachResourceDocument(packId, typeFilter, resources::add);
        return List.copyOf(resources);
    }

    /**
     * Visits canonical resource documents one file at a time. The summary
     * projection uses this to avoid retaining every parsed YAML tree while
     * building its small derived index.
     */
    public void forEachResourceDocument(
            String packId, String typeFilter, Consumer<ResourceDocument> visitor) {
        forEachResourceDocument(packId, typeFilter, visitor, null);
    }

    public void forEachResourceDocument(
            String packId,
            String typeFilter,
            Consumer<ResourceDocument> visitor,
            SummaryReadBudget readBudget) {
        Objects.requireNonNull(visitor, "visitor");
        if (packId != null && !packId.matches("[a-z0-9_-]{2,64}")) {
            throw new IllegalArgumentException("bad pack id: " + packId);
        }
        String safeType = typeFilter == null ? null : typeFilter.toLowerCase(Locale.ROOT);
        if (safeType != null && !TYPES.contains(safeType)) {
            return;
        }
        forEachSummaryPackDirectory(packId, readBudget, packDir -> {
            String pack = packDir.getFileName().toString();
            for (String type : TYPES) {
                if (safeType != null && !safeType.equals(type)) {
                    continue;
                }
                Path typeDir = packDir.resolve(type);
                if (Files.isSymbolicLink(typeDir)
                        || !Files.isDirectory(typeDir, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                consumeInventoryEntry(readBudget, type.length() + 1L);
                collectResourceDocuments(pack, type, typeDir, visitor, readBudget);
            }
        });
    }

    /** Whether a pack has a safe canonical manifest root. */
    public boolean hasSummaryPack(String packId) {
        if (packId == null || !packId.matches("[a-z0-9_-]{2,64}")) {
            return false;
        }
        Path root = packsRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path packDir = root.resolve(packId);
        if (Files.isSymbolicLink(packDir)
                || !Files.isDirectory(packDir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path manifest = packDir.resolve("pack.yml");
        return !Files.isSymbolicLink(manifest)
                && Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS);
    }

    private void forEachSummaryPackDirectory(
            String requestedPack, SummaryReadBudget readBudget, Consumer<Path> visitor) {
        Path root = packsRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (requestedPack != null) {
            if (hasSummaryPack(requestedPack)) {
                consumeInventoryEntry(readBudget, requestedPack.length());
                visitor.accept(root.resolve(requestedPack));
            }
            return;
        }
        try (Stream<Path> children = Files.list(root)) {
            children.forEach(child -> {
                String id = child.getFileName().toString();
                consumeInventoryEntry(readBudget, id.length());
                if (!id.matches("[a-z0-9_-]{2,64}") || Files.isSymbolicLink(child)
                        || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                Path manifest = child.resolve("pack.yml");
                if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    visitor.accept(child);
                }
            });
        } catch (IOException e) {
            // A missing/unreadable packs root contributes no visible packs.
        }
    }

    private void collectResourceDocuments(
            String pack, String type, Path root, Consumer<ResourceDocument> visitor,
            SummaryReadBudget readBudget) {
        Deque<DirectoryFrame> pending = new ArrayDeque<>();
        pending.push(new DirectoryFrame(root, type + "/"));
        while (!pending.isEmpty()) {
            DirectoryFrame frame = pending.pop();
            try (Stream<Path> children = Files.list(frame.path())) {
                children.forEach(child -> {
                    String segment = child.getFileName().toString();
                    consumeInventoryEntry(readBudget, (long) frame.prefix().length() + segment.length());
                    if (Files.isSymbolicLink(child)) {
                        return;
                    }
                    String name = frame.prefix() + segment;
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        pending.push(new DirectoryFrame(child, name + "/"));
                    } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                            && (name.endsWith(".yml") || name.endsWith(".yaml"))) {
                        if (safeSummaryDocumentPath(name)) {
                            addResourceDocument(pack, type, name, child, visitor, readBudget);
                        }
                    }
                });
            } catch (IOException e) {
                // An unreadable subtree is omitted; no caller-supplied path is followed.
            }
        }
    }

    private static boolean safeSummaryDocumentPath(String path) {
        return path != null && !path.contains("..") && !path.startsWith("/")
                && path.matches("[A-Za-z0-9_./ -]+");
    }

    private static void addResourceDocument(
            String pack,
            String type,
            String relative,
            Path file,
            Consumer<ResourceDocument> visitor,
            SummaryReadBudget readBudget) {
        byte[] bytes;
        int readLimit = ModelTransactionService.MAX_TRANSACTION_BYTES + 1;
        if (readBudget != null) {
            long remaining = readBudget.remainingBytes();
            if (remaining <= 0) {
                readBudget.requireRemainingCapacity();
            }
            readLimit = (int) Math.min(readLimit, remaining);
        }
        CountingInputStream input;
        try {
            input = new CountingInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException e) {
            visitor.accept(unreadableResource(pack, type, relative, "document could not be read"));
            return;
        }
        try (input) {
            bytes = input.readNBytes(readLimit);
        } catch (IOException e) {
            if (readBudget != null) {
                readBudget.consume(input.bytesRead());
            }
            visitor.accept(unreadableResource(pack, type, relative, "document could not be read"));
            return;
        }
        if (readBudget != null) {
            readBudget.consume(bytes.length);
            if (bytes.length == readLimit
                    && readLimit < ModelTransactionService.MAX_TRANSACTION_BYTES + 1) {
                readBudget.requireRemainingCapacity();
            }
        }
        if (bytes.length > ModelTransactionService.MAX_TRANSACTION_BYTES) {
            visitor.accept(unreadableResource(
                    pack, type, relative, "document exceeds the bounded summary read limit"));
            return;
        }
        LosslessYamlDocument document;
        try {
            document = LosslessYaml.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            visitor.accept(unreadableResource(pack, type, relative, "document could not be parsed"));
            return;
        }
        if (document.parseError().isPresent()) {
            visitor.accept(new ResourceDocument(
                    new ResourceRef(pack, type, relative, "<parse error>"), document, null));
            return;
        }
        document.data().keySet().forEach(id -> visitor.accept(new ResourceDocument(
                new ResourceRef(pack, type, relative, String.valueOf(id)), document, null)));
    }

    private static ResourceDocument unreadableResource(
            String pack, String type, String relative, String reason) {
        return new ResourceDocument(
                new ResourceRef(pack, type, relative, "<unreadable>"), null, reason);
    }

    private static void consumeInventoryEntry(SummaryReadBudget readBudget, long pathChars) {
        if (readBudget != null) {
            readBudget.consume(64L + 2L * pathChars);
        }
    }

    private record DirectoryFrame(Path path, String prefix) {}

    private static final class CountingInputStream extends FilterInputStream {
        private long bytesRead;

        CountingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        long bytesRead() {
            return bytesRead;
        }
    }

    private void collectRefs(String pack, String type, Path dir, String prefix,
            String query, List<ResourceRef> out) {
        try (Stream<Path> children = Files.list(dir)) {
            children.sorted().forEach(child -> {
                String name = prefix + child.getFileName();
                if (Files.isDirectory(child)) {
                    collectRefs(pack, type, child, name + "/", query, out);
                    return;
                }
                if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
                    return;
                }
                String relative = type + "/" + name;
                for (String id : topLevelKeys(child)) {
                    ResourceRef ref = new ResourceRef(pack, type, relative, id);
                    if (query == null || (pack + ":" + id + " " + relative)
                            .toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                        out.add(ref);
                    }
                }
            });
        } catch (IOException e) {
            // unreadable subtree contributes nothing (D6)
        }
    }

    /** Top-level mapping keys of one YAML file — the resource ids it holds. */
    private List<String> topLevelKeys(Path file) {
        try {
            LosslessYamlDocument document =
                    LosslessYaml.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (document.parseError().isPresent()) {
                // A broken file still shows up in the browser: quarantined,
                // visible, here's why — never silently dropped (plugin.md §5).
                return List.of("<parse error>");
            }
            List<String> keys = new ArrayList<>();
            document.data().keySet().forEach(key -> keys.add(String.valueOf(key)));
            return keys;
        } catch (IOException | RuntimeException e) {
            return List.of("<unreadable>");
        }
    }

    // ------------------------------------------------------------------
    // reads

    /** Reads one document with its current revision. */
    public Document read(String pack, String path) throws IOException {
        Path file = resolve(pack, path);
        if (!Files.isRegularFile(file)) {
            throw new java.nio.file.NoSuchFileException(path);
        }
        synchronized (lock) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String hash = Documents.sha256(raw);
            int revision = syncRevision(pack, path, hash);
            return new Document(pack, path, raw, revision, hash);
        }
    }

    /**
     * Watcher seam: journals an {@code external} revision when the file's
     * current bytes drifted from the journal — the same rule {@link #read}
     * applies, so a hand edit becomes visible history without a full read.
     * A file with no journal yet is baselined as external on first sighting
     * (a hand-created document becomes visible history too), while an
     * editor's own save echoes back as unchanged because its write already
     * journaled.
     */
    public record ExternalSync(int revision, boolean changed) {

        /** File absent: a deletion — pushed to editors, never journaled. */
        public static ExternalSync absent() {
            return new ExternalSync(-1, true);
        }
    }

    public ExternalSync syncExternal(String pack, String path) throws IOException {
        Path file = resolve(pack, path);
        synchronized (lock) {
            if (!Files.isRegularFile(file)) {
                return ExternalSync.absent();
            }
            String hash = Documents.sha256(Files.readAllBytes(file));
            Revision last = lastRevision(pack, path);
            if (last == null) {
                appendRevision(pack, path, new Revision(
                        1, System.currentTimeMillis(), "external", "external", hash));
                return new ExternalSync(1, true);
            }
            int revision = syncRevision(pack, path, hash);
            return new ExternalSync(revision, revision != last.rev());
        }
    }

    /** The journal tail for one document, newest last. */
    public List<Revision> history(String pack, String path, int limit) throws IOException {
        Path journal = journalFor(pack, path);
        if (!Files.isRegularFile(journal)) {
            return List.of();
        }
        synchronized (lock) {
            List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - Math.max(1, limit));
            List<Revision> revisions = new ArrayList<>();
            for (String line : lines.subList(from, lines.size())) {
                parseRevision(line).ifPresent(revisions::add);
            }
            return revisions;
        }
    }

    // ------------------------------------------------------------------
    // writes

    public Checkpoint checkpoint(String pack, String path) throws IOException {
        synchronized (lock) {
            Path document = resolve(pack, path);
            Path journal = journalFor(pack, path);
            return new Checkpoint(
                    pack,
                    path,
                    Files.isRegularFile(document),
                    Files.isRegularFile(document) ? Files.readAllBytes(document) : new byte[0],
                    Files.isRegularFile(journal),
                    Files.isRegularFile(journal) ? Files.readAllBytes(journal) : new byte[0]);
        }
    }

    /** Restores a checkpoint after a composite operation failed. */
    public void restore(Checkpoint checkpoint) throws IOException {
        synchronized (lock) {
            restoreFile(resolve(checkpoint.pack(), checkpoint.path()),
                    checkpoint.documentExisted(), checkpoint.documentBytes());
            restoreFile(journalFor(checkpoint.pack(), checkpoint.path()),
                    checkpoint.journalExisted(), checkpoint.journalBytes());
        }
    }

    private static void restoreFile(Path path, boolean existed, byte[] bytes) throws IOException {
        if (!existed) {
            Files.deleteIfExists(path);
            return;
        }
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".rollback-" + System.nanoTime());
        Files.write(temp, bytes);
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes one document atomically after a stale-write check. Returns the
     * new revision. Throws {@link DocumentConflictException} when
     * {@code expectedRevision} no longer matches; rejects syntactically
     * invalid YAML before touching disk (semantic validation belongs to the
     * pipeline run the host triggers afterwards).
     */
    public int write(String pack, String path, int expectedRevision, String content,
            String actor) throws IOException {
        Objects.requireNonNull(content, "content");
        Optional<String> parseError = LosslessYaml.parse(content).parseError();
        if (parseError.isPresent()) {
            throw new IllegalArgumentException("invalid YAML: " + parseError.get());
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        synchronized (lock) {
            Path file = resolve(pack, path);
            assertExpectedRevision(pack, path, expectedRevision);
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + System.nanoTime());
            Files.write(temp, bytes);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            String hash = Documents.sha256(bytes);
            appendRevision(pack, path, new Revision(
                    currentRevision(pack, path) + 1, System.currentTimeMillis(),
                    actor, "write", hash));
            trimJournal(pack, path);
            return currentRevision(pack, path);
        }
    }

    /** Deletes one document (journaled). Same stale-write semantics as write. */
    public void delete(String pack, String path, int expectedRevision, String actor)
            throws IOException {
        synchronized (lock) {
            Path file = resolve(pack, path);
            assertExpectedRevision(pack, path, expectedRevision);
            Files.deleteIfExists(file);
            appendRevision(pack, path, new Revision(
                    currentRevision(pack, path) + 1, System.currentTimeMillis(),
                    actor, "delete", null));
        }
    }

    /**
     * Patches one scalar value deep in a document <em>through the lossless
     * layer</em>: comments, ordering and untouched formatting survive byte
     * for byte (master §5.6 — one-field-touch → one-line diff). This is what
     * lets the editor's forms be real without ever re-serializing YAML
     * client-side. Same stale-write semantics as {@link #write}.
     *
     * @return the resulting document (new revision + new raw text)
     */
    public Document setScalar(String pack, String path, int expectedRevision,
            List<String> fieldPath, Object value, String actor) throws IOException {
        synchronized (lock) {
            Path file = resolve(pack, path);
            assertExpectedRevision(pack, path, expectedRevision);
            LosslessYamlDocument document =
                    LosslessYaml.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (document.parseError().isPresent()) {
                throw new IllegalStateException(
                        "document does not parse: " + document.parseError().get());
            }
            String updated;
            try {
                updated = document.withScalar(value, fieldPath.toArray(String[]::new)).save();
            } catch (UnsupportedOperationException e) {
                // flow-style / sequence interiors are not line-editable —
                // say so instead of re-serializing the whole file
                throw new IllegalArgumentException(e.getMessage());
            }
            byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            appendRevision(pack, path, new Revision(
                    currentRevision(pack, path) + 1, System.currentTimeMillis(),
                    actor, "set-scalar", Documents.sha256(bytes)));
            trimJournal(pack, path);
            return new Document(pack, path, updated,
                    currentRevision(pack, path), Documents.sha256(bytes));
        }
    }

    /**
     * Applies several scalar patches as <em>one</em> write: the studio saves a
     * whole screen of fields and the author gets one revision, one journal
     * entry and one reload — not one per keystroke-completed field. Every edit
     * goes through the same lossless layer as {@link #setScalar}, and the
     * batch is applied in memory first, so a rejected edit leaves the file
     * untouched.
     *
     * @param edits field path → new value, applied in order
     */
    public Document setScalars(String pack, String path, int expectedRevision,
            List<FieldEdit> edits, String actor) throws IOException {
        if (edits.isEmpty()) {
            throw new IllegalArgumentException("no edits to apply");
        }
        synchronized (lock) {
            Path file = resolve(pack, path);
            assertExpectedRevision(pack, path, expectedRevision);
            LosslessYamlDocument document =
                    LosslessYaml.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (document.parseError().isPresent()) {
                throw new IllegalStateException(
                        "document does not parse: " + document.parseError().get());
            }
            String updated;
            try {
                for (FieldEdit edit : edits) {
                    document = document.withNode(
                            edit.value(), edit.field().toArray(String[]::new));
                }
                updated = document.save();
            } catch (UnsupportedOperationException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
            byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            appendRevision(pack, path, new Revision(
                    currentRevision(pack, path) + 1, System.currentTimeMillis(),
                    actor, "set-scalar", Documents.sha256(bytes)));
            trimJournal(pack, path);
            return new Document(pack, path, updated,
                    currentRevision(pack, path), Documents.sha256(bytes));
        }
    }

    /** One field patch inside a batched studio save. */
    public record FieldEdit(List<String> field, Object value) { }

    /** Creates a minimal pack scaffold ({@code pack.yml} + type dirs). */
    public void createPack(String packId, String actor) throws IOException {
        validatePackId(packId);
        synchronized (lock) {
            createPackLocked(packId, actor);
        }
    }

    /**
     * Creates an optional pack and its first resource as one store operation.
     * Validation runs before any write; if a filesystem failure occurs after
     * creating a new pack, all files and journals from that pack are removed.
     */
    public Document createResource(
            String pack, String path, String content, boolean createPack, String actor)
            throws IOException {
        validatePackId(pack);
        Optional<String> parseError = LosslessYaml.parse(content).parseError();
        if (parseError.isPresent()) {
            throw new IllegalArgumentException("invalid YAML: " + parseError.get());
        }
        synchronized (lock) {
            Path packDir = packsRoot.resolve(pack);
            if (createPack && Files.exists(packDir)) {
                throw new IllegalStateException("pack already exists: " + pack);
            }
            if (!createPack && !Files.isRegularFile(packDir.resolve("pack.yml"))) {
                throw new IllegalStateException("pack does not exist: " + pack);
            }
            try {
                if (createPack) {
                    createPackLocked(pack, actor);
                }
                createDocument(pack, path, content, actor);
                return read(pack, path);
            } catch (IOException | RuntimeException failure) {
                if (createPack) {
                    deleteTree(packDir);
                    deleteTree(revisionsDir.resolve(pack));
                }
                throw failure;
            }
        }
    }

    private void createPackLocked(String packId, String actor) throws IOException {
        Path packDir = packsRoot.resolve(packId);
        if (Files.exists(packDir)) {
            throw new IllegalStateException("pack already exists: " + packId);
        }
        for (String type : TYPES) {
            Files.createDirectories(packDir.resolve(type));
        }
        String manifest = "# created by the Materione editor\n"
                + "id: " + packId + "\n"
                + "version: 0.0.1\n"
                + "schema_version: 1\n"
                + "\n"
                + "depends: []\n";
        write(packId, "pack.yml", 0, manifest, actor);
    }

    private static void validatePackId(String packId) {
        if (!packId.matches("[a-z0-9_-]{2,32}")) {
            throw new IllegalArgumentException(
                    "pack id must be 2..32 chars of a-z 0-9 _ - : " + packId);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // The original transaction failure remains authoritative.
        }
    }

    /**
     * Creates a document that must not exist yet (the editor's
     * {@code edit.create}): the existence check happens under the same lock
     * as the write, so a double-submit cannot clobber.
     *
     * @return the fresh document's revision (always 1)
     */
    public int createDocument(String pack, String path, String content, String actor)
            throws IOException {
        Objects.requireNonNull(content, "content");
        Optional<String> parseError = LosslessYaml.parse(content).parseError();
        if (parseError.isPresent()) {
            throw new IllegalArgumentException("invalid YAML: " + parseError.get());
        }
        synchronized (lock) {
            Path file = resolve(pack, path);
            if (Files.exists(file)) {
                throw new IllegalStateException("document already exists: " + path);
            }
            Files.createDirectories(file.getParent());
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            appendRevision(pack, path, new Revision(
                    1, System.currentTimeMillis(), actor, "create", Documents.sha256(bytes)));
            trimJournal(pack, path);
            return 1;
        }
    }

    /**
     * Moves/renames a document (content unchanged): the target's journal
     * opens at rev 1 recording where it came from, and the source's journal
     * closes with a delete entry — so stale writes against either path fail
     * honestly instead of resurrecting a ghost copy. Same stale-write
     * semantics as {@link #write} on the source revision.
     *
     * @return the moved document at its new path
     */
    public Document move(String pack, String from, int expectedRevision, String to, String actor)
            throws IOException {
        if (from.equals(to)) {
            throw new IllegalArgumentException("move source and target are the same: " + from);
        }
        synchronized (lock) {
            Path source = resolve(pack, from);
            Path target = resolve(pack, to);
            assertExpectedRevision(pack, from, expectedRevision);
            if (!Files.isRegularFile(source)) {
                throw new java.nio.file.NoSuchFileException(from);
            }
            if (Files.exists(target)) {
                throw new IllegalStateException("target already exists: " + to);
            }
            byte[] bytes = Files.readAllBytes(source);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            Files.delete(source);
            String hash = Documents.sha256(bytes);
            appendRevision(pack, to, new Revision(
                    1, System.currentTimeMillis(), actor, "move-from:" + from, hash));
            trimJournal(pack, to);
            appendRevision(pack, from, new Revision(
                    currentRevision(pack, from) + 1, System.currentTimeMillis(),
                    actor, "delete", null));
            return new Document(pack, to, new String(bytes, StandardCharsets.UTF_8), 1, hash);
        }
    }

    // ------------------------------------------------------------------
    // folders

    /**
     * Pack-relative directories under the type roots — the organisational
     * skeleton the content browser navigates. Type roots themselves are
     * included so an empty type still browses as an existing folder.
     */
    public List<String> listFolders(String packId) {
        List<String> folders = new ArrayList<>();
        Path packDir = packsRoot.resolve(packId);
        for (String type : TYPES) {
            Path typeDir = packDir.resolve(type);
            if (!Files.isDirectory(typeDir)) {
                continue;
            }
            folders.add(type);
            collectFolders(typeDir, type, folders);
        }
        folders.sort(String::compareTo);
        return folders;
    }

    private void collectFolders(Path dir, String prefix, List<String> out) {
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String relative = prefix + "/" + child.getFileName();
                out.add(relative);
                collectFolders(child, relative, out);
            }
        } catch (IOException e) {
            // unreadable subtree contributes nothing (D6)
        }
    }

    /** Creates an empty organisational folder under a type root. */
    public void createFolder(String pack, String path) throws IOException {
        synchronized (lock) {
            Path dir = resolveFolder(pack, path);
            if (Files.exists(dir)) {
                throw new IllegalStateException("folder already exists: " + path);
            }
            Files.createDirectories(dir);
        }
    }

    /** Deletes an empty folder; a folder with content is never dropped. */
    public void deleteFolder(String pack, String path) throws IOException {
        synchronized (lock) {
            Path dir = resolveFolder(pack, path);
            if (!Files.isDirectory(dir)) {
                throw new java.nio.file.NoSuchFileException(path);
            }
            try (Stream<Path> children = Files.list(dir)) {
                if (children.findAny().isPresent()) {
                    throw new IllegalStateException(
                            "folder is not empty: " + path);
                }
            }
            Files.delete(dir);
        }
    }

    /**
     * Renames/moves a folder by moving every document it holds, so each file
     * keeps honest revision history at its new path. Validation runs over the
     * whole set before the first move: a conflicting target or a stale
     * revision aborts before anything is touched.
     */
    public void moveFolder(String pack, String from, String to, String actor) throws IOException {
        if (from.equals(to)) {
            throw new IllegalArgumentException("folder source and target are the same: " + from);
        }
        synchronized (lock) {
            Path source = resolveFolder(pack, from);
            Path target = resolveFolder(pack, to);
            if (!from.contains("/") || !to.contains("/")) {
                throw new IllegalArgumentException("type roots cannot be moved: " + from);
            }
            if (!source.startsWith(packsRoot.resolve(pack).resolve(typeOf(from)))
                    || !target.startsWith(packsRoot.resolve(pack).resolve(typeOf(from)))) {
                throw new IllegalArgumentException("folders move within their own type: " + to);
            }
            if (!Files.isDirectory(source)) {
                throw new java.nio.file.NoSuchFileException(from);
            }
            if (Files.exists(target)) {
                throw new IllegalStateException("target already exists: " + to);
            }
            List<String> documents = new ArrayList<>();
            for (ResourceRef ref : listResources(pack, typeOf(from), null)) {
                if (ref.path().startsWith(from + "/") && !documents.contains(ref.path())) {
                    documents.add(ref.path());
                }
            }
            for (String path : documents) {
                if (Files.exists(resolve(pack, to + path.substring(from.length())))) {
                    throw new IllegalStateException(
                            "target already exists: " + to + path.substring(from.length()));
                }
            }
            for (String path : documents) {
                move(pack, path, currentRevisionOf(pack, path),
                        to + path.substring(from.length()), actor);
            }
            deleteEmptyTree(source);
        }
    }

    private int currentRevisionOf(String pack, String path) throws IOException {
        return read(pack, path).revision();
    }

    private static String typeOf(String folder) {
        int slash = folder.indexOf('/');
        return slash < 0 ? folder : folder.substring(0, slash);
    }

    private static void deleteEmptyTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (Files.isDirectory(path)) {
                    try (Stream<Path> children = Files.list(path)) {
                        if (children.findAny().isEmpty()) {
                            Files.delete(path);
                        }
                    }
                }
            }
        }
    }

    /** Resolves and safety-checks a pack-relative folder under a type root. */
    private Path resolveFolder(String pack, String path) {
        if (pack == null || !pack.matches("[a-z0-9_-]{2,64}")) {
            throw new IllegalArgumentException("bad pack id: " + pack);
        }
        if (path == null || path.contains("..") || path.startsWith("/") || path.endsWith("/")
                || !path.matches("[A-Za-z0-9_/ -]+")) {
            throw new IllegalArgumentException("bad folder path: " + path);
        }
        if (TYPES.stream().noneMatch(type -> path.equals(type) || path.startsWith(type + "/"))) {
            throw new IllegalArgumentException("folders live under a content type: " + path);
        }
        Path resolved = packsRoot.resolve(pack).resolve(path).normalize();
        if (!resolved.startsWith(packsRoot.resolve(pack))) {
            throw new IllegalArgumentException("path escapes its pack: " + path);
        }
        return resolved;
    }

    // ------------------------------------------------------------------

    /** Resolves and safety-checks a pack-relative path. */
    private Path resolve(String pack, String path) {
        if (pack == null || !pack.matches("[a-z0-9_-]{2,64}")) {
            throw new IllegalArgumentException("bad pack id: " + pack);
        }
        if (path == null || path.contains("..") || path.startsWith("/")
                || !path.matches("[A-Za-z0-9_./ -]+")) {
            throw new IllegalArgumentException("bad document path: " + path);
        }
        boolean editable = path.equals("pack.yml")
                || TYPES.stream().anyMatch(type -> path.startsWith(type + "/"));
        if (!editable) {
            throw new IllegalArgumentException("path is not an editable document: " + path);
        }
        Path resolved = packsRoot.resolve(pack).resolve(path).normalize();
        if (!resolved.startsWith(packsRoot.resolve(pack))) {
            throw new IllegalArgumentException("path escapes its pack: " + path);
        }
        return resolved;
    }

    /**
     * Journals an {@code external} revision when the file hash drifted from
     * the journal's last recorded state; returns the current revision.
     */
    private int syncRevision(String pack, String path, String fileHash) throws IOException {
        Revision last = lastRevision(pack, path);
        if (last != null && last.action().equals("delete")) {
            // File reappeared after deletion — treat as fresh external content.
            appendRevision(pack, path, new Revision(last.rev() + 1, System.currentTimeMillis(),
                    "external", "external", fileHash));
            return last.rev() + 1;
        }
        if (last != null && !fileHash.equals(last.sha256())) {
            appendRevision(pack, path, new Revision(last.rev() + 1, System.currentTimeMillis(),
                    "external", "external", fileHash));
            trimJournal(pack, path);
            return last.rev() + 1;
        }
        return last == null ? 0 : last.rev();
    }

    private void assertExpectedRevision(String pack, String path, int expectedRevision)
            throws IOException {
        int current = currentRevision(pack, path);
        Path file = resolve(pack, path);
        if (Files.isRegularFile(file)) {
            current = syncRevision(pack, path, Documents.sha256(Files.readAllBytes(file)));
        }
        if (current != expectedRevision) {
            throw new DocumentConflictException(
                    "document changed elsewhere: expected rev " + expectedRevision
                            + ", current rev " + current,
                    expectedRevision, current);
        }
    }

    private int currentRevision(String pack, String path) throws IOException {
        Revision last = lastRevision(pack, path);
        return last == null ? 0 : last.rev();
    }

    private Revision lastRevision(String pack, String path) throws IOException {
        Path journal = journalFor(pack, path);
        if (!Files.isRegularFile(journal)) {
            return null;
        }
        List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        for (int i = lines.size() - 1; i >= 0; i--) {
            Optional<Revision> parsed = parseRevision(lines.get(i));
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        return null;
    }

    /**
     * Journal lines are Gson-free in core (Gson is server-provided and this
     * layer must stay embeddable): tiny hand parser over a flat shape
     * {@code {"rev":N,"ts":N,"actor":"…","action":"…","sha":"…"|"null"}}.
     */
    private Optional<Revision> parseRevision(String line) {
        try {
            var map = dev.materione.core.document.RevisionJson.parse(line);
            if (map == null) {
                return Optional.empty();
            }
            return Optional.of(new Revision(
                    Integer.parseInt(map.get("rev")),
                    Long.parseLong(map.get("ts")),
                    unquote(map.get("actor")),
                    unquote(map.get("action")),
                    "null".equals(map.get("sha")) ? null : unquote(map.get("sha"))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    private void appendRevision(String pack, String path, Revision revision) throws IOException {
        Path journal = journalFor(pack, path);
        Files.createDirectories(journal.getParent());
        String line = RevisionJson.format(revision);
        if (!Files.exists(journal)) {
            Files.writeString(journal, line + "\n", StandardCharsets.UTF_8);
        } else {
            Files.writeString(journal, line + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        }
    }

    /** Keeps the journal bounded: the tail carries recent truth; rev numbers never reset. */
    private void trimJournal(String pack, String path) throws IOException {
        Path journal = journalFor(pack, path);
        List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        if (lines.size() > JOURNAL_CAP) {
            Files.writeString(journal,
                    String.join("\n", lines.subList(lines.size() - JOURNAL_CAP, lines.size())) + "\n",
                    StandardCharsets.UTF_8);
        }
    }

    private Path journalFor(String pack, String path) {
        return revisionsDir.resolve(pack).resolve(path + ".jsonl");
    }
}
