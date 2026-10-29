package dev.materione.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A fresh server workspace under the work root: eula, minimal offline
 * flat-world server properties, the built plugin installed, and bStats
 * opted out. The whole directory lives under {@code build/} so a stray kill
 * can never dirty the repo.
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
        return create(options, scenarioName, null);
    }

    /**
     * Creates a workspace carrying only the named packs (null = every pack
     * directory under the fixture root). Scenarios filter so their expected
     * live counts stay pinned: bootstrap runs sample+corpus (56 items),
     * the world-persistence scenario adds {@code world}.
     */
    public static ServerWorkspace create(HarnessOptions options, String scenarioName,
            java.util.Set<String> packNames) throws IOException {
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
        // bStats usage metrics are opt-out per server: automated boots never report.
        Path bstats = root.resolve("plugins").resolve("bStats");
        Files.createDirectories(bstats);
        Files.writeString(bstats.resolve("config.yml"), "enabled: false\n");

        ServerWorkspace workspace = new ServerWorkspace(root);
        // Ports for local runs whose defaults are taken (another server on the machine): the CI pins
        // stay the defaults; only an explicit override writes a settings file
        Integer editorPort = Integer.getInteger("harness.editorPort");
        Integer deliveryPort = Integer.getInteger("harness.deliveryPort");
        if (editorPort != null || deliveryPort != null) {
            Path settings = root.resolve("plugins").resolve("Materione").resolve("settings");
            Files.createDirectories(settings);
            var yaml = new StringBuilder("version: 1\n");
            if (editorPort != null) {
                yaml.append("editor_port: ").append(editorPort).append('\n');
            }
            if (deliveryPort != null) {
                yaml.append("delivery_port: ").append(deliveryPort).append('\n');
            }
            Files.writeString(settings.resolve("settings.yml"), yaml.toString());
        }
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
                    if (packNames != null && !packNames.contains(pack.getFileName().toString())) {
                        continue;
                    }
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
     * syntax, not a typo. A dedicated high port keeps harness boots
     * independent of any live server on the machine's 25565.
     */
    private static String serverProperties() {
        return """
                server-port=%d
                online-mode=false
                level-type=minecraft\\:flat
                generate-structures=false
                view-distance=2
                simulation-distance=2
                spawn-protection=0
                max-players=1
                motd=MaterioneHarness
                """.formatted(Integer.getInteger("harness.serverPort", 25599));
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
