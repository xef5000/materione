package dev.materione.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A fresh server workspace under the work root: eula, minimal offline
 * flat-world server properties, and the built plugin installed. The whole
 * directory lives under {@code build/} so a stray kill can never dirty the
 * repo.
 */
public final class ServerWorkspace {

    public final Path root;
    public final Path pluginsDir;
    public final Path logFile;
    public final Path stdoutFile;

    private ServerWorkspace(Path root) {
        this.root = root;
        this.pluginsDir = root.resolve("plugins");
        this.logFile = root.resolve("logs").resolve("latest.log");
        this.stdoutFile = root.resolve("stdout.log");
    }

    public static ServerWorkspace create(HarnessOptions options, String scenarioName) throws IOException {
        Path root = options.workRoot().resolve(scenarioName);
        if (Files.exists(root)) {
            deleteRecursively(root);
        }
        Files.createDirectories(root.resolve("plugins"));
        Files.createDirectories(root.resolve("logs"));

        Files.writeString(root.resolve("eula.txt"), "eula=true\n");
        Files.writeString(root.resolve("server.properties"), serverProperties());

        // The harness's own ITs may inject a broken settings file AFTER this
        // call but BEFORE boot (fail-soft scenario).
        Files.copy(options.pluginJar(), root.resolve("plugins").resolve("Materione.jar"),
                StandardCopyOption.REPLACE_EXISTING);

        ServerWorkspace workspace = new ServerWorkspace(root);
        if (options.brokenSettingsYaml() != null) {
            Path settings = root.resolve("plugins").resolve("Materione").resolve("settings");
            Files.createDirectories(settings);
            Files.writeString(settings.resolve("settings.yml"), options.brokenSettingsYaml());
        }
        if (options.contentPacksDir() != null && Files.isDirectory(options.contentPacksDir())) {
            // optional content packs (fixture corpus) copied verbatim under
            // plugins/Materione/content/packs/ — D1: files are the source of truth
            Path packs = root.resolve("plugins").resolve("Materione").resolve("content").resolve("packs");
            try (var packDirs = Files.list(options.contentPacksDir())) {
                for (Path pack : packDirs.filter(Files::isDirectory).toList()) {
                    copyRecursively(pack, packs.resolve(pack.getFileName()));
                }
            }
        }
        return workspace;
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path file : walk.toList()) {
                Path destination = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Minimal offline flat world: fast boot, deterministic, no structures,
     * tiny view/simulation distance. The escaped colon is server.properties
     * syntax, not a typo.
     */
    private static String serverProperties() {
        return """
                online-mode=false
                level-type=minecraft\\:flat
                generate-structures=false
                view-distance=2
                simulation-distance=2
                spawn-protection=0
                max-players=1
                motd=MaterioneHarness
                """;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
        }
    }
}
