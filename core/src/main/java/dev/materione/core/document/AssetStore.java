package dev.materione.core.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The filesystem layer under {@code content/packs/<pack>/assets/} (web.md
 * §4.2 "assets" group), extracted from the editor RPC surface so core owns
 * every pack mutation (D1). Writes are size-capped, path-sanitized, atomic
 * (temp sibling + move) and audited exactly like the editor always did; the
 * caller decides what a change means for the live pipeline — each mutation
 * names the pack it touched, so the host can queue a whole-pack reload
 * (texture edits can affect any referencing item).
 *
 * <p>Reads may also reach the model renders the Blockbench workbench caches
 * under {@code .materione/thumbnails/}; writes never can — authoring
 * metadata is written only by the model transaction that produces it.
 */
public final class AssetStore {

    /** Per-asset cap for textures/models: they are KBs to low-MBs at most. */
    public static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;

    /**
     * Audio rides the same sealed frames but is bulkier — a song-length
     * .ogg at a sensible bitrate needs more headroom (M4). The wire frame
     * budget is the real ceiling; larger sources wait for M5's chunk flow.
     */
    public static final int MAX_AUDIO_BYTES = 8 * 1024 * 1024;

    private static final Set<String> AUDIO_TYPES = Set.of("ogg", "oga");

    /** One listed asset: path relative to the pack root, size and kind (extension). */
    public record AssetEntry(String path, long sizeBytes, String kind) {}

    /** Read-only outcome for a path admitted by the existing asset-read policy. */
    public enum ReadableAssetState {
        PRESENT,
        MISSING,
        UNAVAILABLE
    }

    private final Path packsRoot;
    private final AuditLog audit;

    public AssetStore(Path packsRoot, AuditLog audit) {
        this.packsRoot = packsRoot;
        this.audit = audit;
    }

    /** The size cap that applies to one asset path (audio gets headroom). */
    public static int capFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot + 1);
        return AUDIO_TYPES.contains(extension) ? MAX_AUDIO_BYTES : MAX_ASSET_BYTES;
    }

    /** Every asset of one pack, sorted by path; an absent directory lists empty. */
    public List<AssetEntry> list(String pack) throws IOException {
        List<AssetEntry> entries = new ArrayList<>();
        Path assetsDir = packsRoot.resolve(pack).resolve("assets");
        if (!Files.isDirectory(assetsDir)) {
            return entries;
        }
        try (Stream<Path> stream = Files.walk(assetsDir)) {
            stream.filter(Files::isRegularFile).sorted().forEach(file -> {
                String relative = packsRoot.resolve(pack).relativize(file)
                        .toString().replace('\\', '/');
                entries.add(new AssetEntry(relative, sizeOf(file), kindOf(relative)));
            });
        }
        return entries;
    }

    /**
     * Atomic size-capped write. Returns the byte count written; the caller
     * queues the pack reload.
     */
    public int put(String actor, String pack, String path, byte[] bytes) throws IOException {
        String safe = safeAssetPath(path);
        int cap = capFor(safe);
        if (bytes.length > cap) {
            throw new IllegalArgumentException(
                    "asset exceeds the " + (cap / 1024) + " KiB limit");
        }
        Path file = target(pack, safe);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + System.nanoTime());
        Files.write(temp, bytes);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        audit.record(actor, "assets.put", pack + "/" + safe);
        return bytes.length;
    }

    /** Reads one asset's bytes; the thumbnail cache is readable, nothing else outside {@code assets/}. */
    public byte[] get(String pack, String path) throws IOException {
        Path file = packsRoot.resolve(pack)
                .resolve(readableAssetPath(path))
                .normalize();
        if (!file.startsWith(packsRoot.resolve(pack)) || !Files.isRegularFile(file)) {
            throw new java.nio.file.NoSuchFileException(path);
        }
        return Files.readAllBytes(file);
    }

    /**
     * Checks a readable path without loading its bytes. This deliberately
     * shares {@link #readableAssetPath(String)} with {@link #get(String, String)}
     * so summaries cannot expose paths outside assets or cached thumbnails.
     */
    public ReadableAssetState inspectReadable(String pack, String path) {
        final String safePath;
        try {
            safePath = readableAssetPath(path);
        } catch (IllegalArgumentException e) {
            return ReadableAssetState.UNAVAILABLE;
        }
        if (pack == null || !pack.matches("[a-z0-9_-]{2,64}")) {
            return ReadableAssetState.UNAVAILABLE;
        }
        Path root = packsRoot.toAbsolutePath().normalize();
        Path packRoot = root.resolve(pack).normalize();
        if (!packRoot.startsWith(root) || Files.isSymbolicLink(packRoot)) {
            return ReadableAssetState.UNAVAILABLE;
        }
        Path file = packRoot.resolve(safePath).normalize();
        if (!file.startsWith(packRoot)) {
            return ReadableAssetState.UNAVAILABLE;
        }
        Path current = packRoot;
        for (Path segment : packRoot.relativize(file)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return ReadableAssetState.UNAVAILABLE;
            }
        }
        if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return ReadableAssetState.PRESENT;
        }
        return Files.notExists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                ? ReadableAssetState.MISSING
                : ReadableAssetState.UNAVAILABLE;
    }

    /** Atomic rename; the target must not exist. Returns silently only on success. */
    public void move(String actor, String pack, String from, String to) throws IOException {
        from = safeAssetPath(from);
        to = safeAssetPath(to);
        if (from.equals(to)) {
            throw new IllegalArgumentException("move source and target are the same");
        }
        Path source = packsRoot.resolve(pack).resolve(from).normalize();
        Path target = packsRoot.resolve(pack).resolve(to).normalize();
        if (!source.startsWith(packsRoot.resolve(pack))
                || !target.startsWith(packsRoot.resolve(pack))) {
            throw new IllegalArgumentException("asset path escapes its pack: " + from);
        }
        if (!Files.isRegularFile(source)) {
            throw new java.nio.file.NoSuchFileException(from);
        }
        if (Files.exists(target)) {
            throw new IllegalStateException("target already exists: " + to);
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        audit.record(actor, "assets.move", pack + "/" + from + " -> " + to);
    }

    /**
     * Deletes one asset. Returns whether the file existed — only then does
     * the caller queue a reload. Audited either way, exactly as before.
     */
    public boolean delete(String actor, String pack, String path) throws IOException {
        Path file = target(pack, safeAssetPath(path));
        boolean existed = Files.deleteIfExists(file);
        audit.record(actor, "assets.delete", pack + "/" + path);
        return existed;
    }

    // ------------------------------------------------------------------

    /** Resolves and safety-checks a writable asset path inside {@code assets/}. */
    public static String safeAssetPath(String path) {
        if (path == null || !path.matches("assets/[A-Za-z0-9_./ -]+")
                || path.contains("..")) {
            throw new IllegalArgumentException(
                    "asset paths must start with assets/ and stay inside it: " + path);
        }
        return path.replace('\\', '/');
    }

    /**
     * Reads may also reach the model renders the Blockbench workbench caches
     * under {@code .materione/thumbnails/}, so a studio can show a model
     * without booting Blockbench. Writes deliberately cannot: authoring
     * metadata is written by the transaction that produces it
     * ({@link ModelTransactionService}), never by a free-form asset upload.
     */
    public static String readableAssetPath(String path) {
        if (path != null
                && path.matches("\\.materione/thumbnails/[A-Za-z0-9_./-]+\\.png")
                && !path.contains("..")) {
            return path;
        }
        return safeAssetPath(path);
    }

    private Path target(String pack, String safePath) {
        Path file = packsRoot.resolve(pack).resolve(safePath).normalize();
        if (!file.startsWith(packsRoot.resolve(pack))) {
            throw new IllegalArgumentException("asset path escapes its pack: " + safePath);
        }
        return file;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private static String kindOf(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "bin" : lower.substring(dot + 1);
    }
}
