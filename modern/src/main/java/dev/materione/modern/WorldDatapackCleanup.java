package dev.materione.modern;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Removes any deployed content datapack from the world folder(s) — called
 * from the bootstrap, <strong>before</strong> vanilla scans the world's
 * {@code datapacks/}: a leftover artifact (a datapack-era boot, a plugin
 * upgrade, a server upgrade) would register its painting/song entries
 * during datapack load, and every compose registration of the same keys
 * would then fail as a duplicate. Deleting here makes the very first
 * registry-mode boot clean.
 *
 * <p>The server root is derived as the plugin data directory's grandparent
 * ({@code plugins/Materione → plugins → server root}) and the world folder
 * from {@code server.properties}' {@code level-name} (default {@code
 * world}) — conventions every supported setup follows. A failure anywhere
 * is a warning, never a crash (D6): the enable-time {@code DatapackService}
 * cleanup stays as a secondary net.
 */
public final class WorldDatapackCleanup {

    private static final String ZIP_NAME = "materione-content.zip";

    private WorldDatapackCleanup() {}

    public static void removeDeployed(Path dataDirectory, Consumer<String> log) {
        try {
            // Paper may hand out a relative data directory (server cwd) —
            // relativized parents would yield null and silently skip the
            // cleanup, so absolutize before deriving the server root
            Path data = dataDirectory.toAbsolutePath().normalize();
            Path pluginsDir = data.getParent();
            Path serverRoot = pluginsDir == null ? null : pluginsDir.getParent();
            if (serverRoot == null || !Files.isDirectory(serverRoot)) {
                log.accept("MATERIONE-DATAPACK-CLEANUP skipped: could not derive the server"
                        + " root from " + data + " (enable-time cleanup retries)");
                return;
            }
            String levelName = "world";
            Path properties = serverRoot.resolve("server.properties");
            if (Files.isRegularFile(properties)) {
                for (String line : Files.readAllLines(properties)) {
                    if (line.startsWith("level-name=")) {
                        levelName = line.substring("level-name=".length()).trim();
                        break;
                    }
                }
            }
            Path worldRoot = serverRoot.resolve(levelName);
            int removed = remove(worldRoot.resolve("datapacks"));
            // the 26.x world-folder restructure: an earlier implementation
            // deployed into the first world's getWorldFolder()/datapacks —
            // which is the overworld *dimension* directory there; sweep one
            // dimension level so no stale copy survives anywhere
            Path dimensions = worldRoot.resolve("dimensions");
            if (Files.isDirectory(dimensions)) {
                try (var namespaces = Files.list(dimensions)) {
                    for (Path namespace : namespaces.filter(Files::isDirectory).toList()) {
                        try (var dims = Files.list(namespace)) {
                            for (Path dim : dims.filter(Files::isDirectory).toList()) {
                                removed += remove(dim.resolve("datapacks"));
                            }
                        }
                    }
                }
            }
            if (removed > 0) {
                log.accept("MATERIONE-DATAPACK-CLEANUP removed " + removed
                        + " world datapack artifact(s) — registry mode needs no world datapack");
            } else {
                log.accept("MATERIONE-DATAPACK-CLEANUP none found under " + worldRoot);
            }
        } catch (IOException | RuntimeException e) {
            log.accept("world datapack cleanup failed (enable-time cleanup retries): " + e);
        }
    }

    /** Deletes the content zip (and a possible .new staging copy) from one datapacks dir. */
    private static int remove(Path datapacks) throws IOException {
        int removed = 0;
        if (!Files.isDirectory(datapacks)) {
            return 0;
        }
        Path zip = datapacks.resolve(ZIP_NAME);
        if (Files.isRegularFile(zip)) {
            Files.delete(zip);
            removed++;
        }
        Path staging = datapacks.resolve(ZIP_NAME + ".new");
        if (Files.isRegularFile(staging)) {
            Files.delete(staging);
        }
        return removed;
    }
}
