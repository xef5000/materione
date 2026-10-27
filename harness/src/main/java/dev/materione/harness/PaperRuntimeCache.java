package dev.materione.harness;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Only Paper's immutable bootstrap jars cross the fresh-workspace boundary. */
final class PaperRuntimeCache {
    private static final Set<String> ROOTS = Set.of("cache", "libraries", "versions");
    private final Path entry;

    PaperRuntimeCache(Path root, Path paperJar) throws IOException {
        String platform = System.getProperty("os.name").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "-");
        entry = root.resolve("v1-" + platform + "-" + System.getProperty("os.arch")
                + "-java" + Runtime.version().feature()).resolve(hash(paperJar));
    }

    boolean restore(Path workspace) throws IOException {
        if (!Files.exists(entry)) {
            return false;
        }
        Path manifestFile = entry.resolve("manifest.sha256");
        if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid("missing manifest");
        }
        List<String> manifest = Files.readAllLines(manifestFile);
        if (manifest.isEmpty()) {
            throw invalid("empty manifest");
        }
        for (String line : manifest) {
            String[] parts = line.split(" ", 2);
            if (parts.length != 2 || !parts[0].matches("[0-9a-f]{64}")) {
                throw invalid("malformed manifest");
            }
            Path relative = Path.of(parts[1]);
            if (!allowed(relative) || !Files.isRegularFile(entry.resolve(relative), LinkOption.NOFOLLOW_LINKS)
                    || !hash(entry.resolve(relative)).equals(parts[0])) {
                throw invalid("invalid binary " + relative);
            }
        }
        for (String line : manifest) {
            Path relative = Path.of(line.split(" ", 2)[1]);
            Path target = workspace.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(entry.resolve(relative), target, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    void capture(Path workspace) throws IOException {
        Files.createDirectories(entry.getParent());
        try (FileChannel channel = FileChannel.open(entry.resolveSibling(entry.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            var lock = channel.lock();
            try {
                if (Files.isDirectory(entry)) {
                    return;
                }
                Path staging = Files.createTempDirectory(entry.getParent(), "runtime-");
                try {
                    List<String> manifest = new ArrayList<>();
                    for (String root : ROOTS.stream().sorted().toList()) {
                        Path source = workspace.resolve(root);
                        if (!Files.isDirectory(source)) {
                            throw new IOException("Paper bootstrap directory missing after ready: " + source);
                        }
                        try (var files = Files.walk(source)) {
                            for (Path file : files.filter(path ->
                                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                                Path relative = workspace.relativize(file);
                                if (allowed(relative)) {
                                    Path target = staging.resolve(relative);
                                    Files.createDirectories(target.getParent());
                                    Files.copy(file, target);
                                    manifest.add(hash(target) + " " + relative.toString().replace('\\', '/'));
                                }
                            }
                        }
                    }
                    if (manifest.isEmpty()) {
                        throw new IOException("Paper bootstrap produced no cacheable jars in " + workspace);
                    }
                    Files.write(staging.resolve("manifest.sha256"), manifest);
                    Files.move(staging, entry, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    if (Files.exists(staging)) {
                        try (var files = Files.walk(staging)) {
                            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                                Files.delete(file);
                            }
                        }
                    }
                }
            } finally {
                lock.release();
            }
        }
    }

    private IOException invalid(String reason) {
        return new IOException("Paper runtime cache is invalid (" + reason + "): " + entry
                + ". Remove this cache entry or run with -Pharness.runtimeCache=false.");
    }

    private static boolean allowed(Path relative) {
        return !relative.isAbsolute() && relative.getNameCount() >= 2
                && ROOTS.contains(relative.getName(0).toString())
                && relative.normalize().equals(relative)
                && relative.toString().endsWith(".jar");
    }

    private static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = stream.read(buffer)) >= 0; ) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK does not provide SHA-256", e);
        }
    }
}
