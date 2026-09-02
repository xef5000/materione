package dev.materione.core.document;

import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.LosslessYamlDocument;
import java.io.IOException;import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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

    /** Pack-relative directories that hold editable documents (§7.2). */
    public static final List<String> TYPES =
            List.of("items", "blocks", "furnitures", "recipes", "templates");

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

    public record Document(String pack, String path, String raw, int revision, String sha256) {}

    public record Revision(int rev, long timestampMs, String actor, String action, String sha256) {}

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

    /** Creates a minimal pack scaffold ({@code pack.yml} + type dirs). */
    public void createPack(String packId, String actor) throws IOException {
        if (!packId.matches("[a-z0-9_-]{2,32}")) {
            throw new IllegalArgumentException(
                    "pack id must be 2..32 chars of a-z 0-9 _ - : " + packId);
        }
        Path packDir = packsRoot.resolve(packId);
        if (Files.exists(packDir)) {
            throw new IllegalStateException("pack already exists: " + packId);
        }
        synchronized (lock) {
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
