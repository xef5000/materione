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
        return workspace;
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
