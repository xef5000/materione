package dev.materione.core.pipeline;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-source-file SHA-256 fingerprints ({@code generated/fingerprints.bin}):
 * the targeted-reload correctness net. A scoped run (OnePack / FileSet)
 * hashes every file under the packs root — if anything <em>outside</em> its
 * scope drifted since the last full run, the scope expands to everything
 * (SCOPE_EXPANDED) instead of silently missing an edit. Keys are paths
 * relative to the packs root, so the cache survives workspace moves; a
 * missing or torn file reads as "no fingerprints" (full run, safe
 * direction).
 */
public final class FingerprintCache {

    private final Map<String, String> byRelativePath = new ConcurrentHashMap<>();

    /** An empty table. */
    public static FingerprintCache empty() {
        return new FingerprintCache();
    }

    /** Loads fingerprints; never throws for content problems. */
    public static FingerprintCache load(Path file) {
        FingerprintCache cache = new FingerprintCache();
        if (!Files.isRegularFile(file)) {
            return cache;
        }
        try (var in = new DataInputStream(Files.newInputStream(file))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String path = in.readUTF();
                String hash = in.readUTF();
                cache.byRelativePath.put(path, hash);
            }
        } catch (IOException e) {
            cache.byRelativePath.clear(); // torn payload behaves as absent
        }
        return cache;
    }

    /** The stored fingerprint for a relative path, or null. */
    public String fingerprintOf(String relativePath) {
        return byRelativePath.get(relativePath);
    }

    /** Records one file's fingerprint. */
    public void record(String relativePath, String sha256Hex) {
        byRelativePath.put(relativePath, sha256Hex);
    }

    /** Drops fingerprints of files that no longer exist in {@code kept}. */
    public void retainAll(Set<String> keptRelativePaths) {
        byRelativePath.keySet().retainAll(keptRelativePaths);
    }

    /**
     * True when any file outside {@code inScope} differs from its recorded
     * fingerprint (or has none), or any recorded out-of-scope file has been
     * deleted. Deleted files count as drifted — the splice would otherwise
     * serve stale resources forever.
     */
    public boolean driftedOutside(Set<String> inScope, Path packsRoot) throws IOException {
        for (Path file : yamlFiles(packsRoot)) {
            String relative = relative(packsRoot, file);
            if (inScope.contains(relative)) {
                continue;
            }
            String current = sha256(file);
            if (!current.equals(byRelativePath.get(relative))) {
                return true;
            }
        }
        for (String relative : byRelativePath.keySet()) {
            if (!inScope.contains(relative) && !Files.exists(packsRoot.resolve(relative))) {
                return true;
            }
        }
        return false;
    }

    /** Writes the table atomically; failures surface as false (the next run re-hashes). */
    public boolean save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(byRelativePath.size());
                for (Map.Entry<String, String> entry : new HashMap<>(byRelativePath).entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // --- helpers -------------------------------------------------------------

    private static java.util.List<Path> yamlFiles(Path packsRoot) throws IOException {
        var out = new java.util.ArrayList<Path>();
        if (!Files.isDirectory(packsRoot)) {
            return out;
        }
        try (var packs = Files.list(packsRoot)) {
            for (Path pack : packs.filter(Files::isDirectory).sorted().toList()) {
                try (var files = Files.walk(pack)) {
                    files.filter(f -> f.toString().endsWith(".yml") && Files.isRegularFile(f))
                            .sorted()
                            .forEach(out::add);
                }
            }
        }
        return out;
    }

    private static String relative(Path root, Path file) {
        return root.toAbsolutePath().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }

    /** Hex SHA-256 of a file's bytes; unreadable files hash as empty content. */
    static String sha256(Path file) {
        byte[] bytes = new byte[0];
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException ignored) {
            // treated as drifted on comparison — the safe direction
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes)); // JVM-mandated digest fallback
        }
    }

    private FingerprintCache() {}
}
