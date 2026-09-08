package dev.materione.plugin.runtime;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.storage.RestartStaging;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.bukkit.plugin.Plugin;

/**
 * The world-datapack applier (M4, runtime-systems §10): moves the compiled
 * artifact into the default world's {@code datapacks/} folder atomically and
 * keeps the restart-required ledger honest.
 *
 * <p>The honesty model: the artifact carries its content hash inside
 * {@code materione.json}, so the source of truth for "what is in the world
 * folder" is the deployed zip itself — never a side marker, which goes stale
 * the moment the world is regenerated or the zip is removed by hand. {@code
 * activeAtBoot} snapshots the world zip once at boot — that is what the
 * running server actually loaded. Any deploy that differs from that snapshot
 * is staged as restart-required (MTR-0028) and cleared by the boot drain
 * (MTR-0029) after one restart, when the server really loaded it.
 *
 * <p><strong>Registry mode</strong> (servers whose Paper lifecycle supports
 * composing painting variants and jukebox songs — 1.21.6+): nothing is
 * deployed; the content registered at bootstrap is tracked by the same
 * hash ledger, with the compiled artifact as its proxy. A reload that
 * changes the compiled hash stages restart-required exactly like a deploy
 * would, and any world-folder artifact left over from a pre-registry boot
 * (or a server upgrade) is removed — vanilla would register the datapack's
 * entries first and the registry re-registration of the same keys would
 * fail per entry.
 */
public final class DatapackService {

    private static final String ZIP_NAME = "materione-content.zip";
    private static final String MARKER = "datapack-applied.txt";

    private final Plugin plugin;
    private final DiagnosticsBus diagnostics;
    private final Path storageDir;
    private final RestartStaging staging;
    private final boolean registryMode;
    /** The artifact hash the running server actually loaded at boot. */
    private String activeAtBoot;
    private String lastWritten;
    /** Registry mode: the first apply of a boot records the registered content. */
    private boolean bootSnapshotted;

    public DatapackService(
            Plugin plugin,
            DiagnosticsBus diagnostics,
            Path storageDir,
            RestartStaging staging,
            boolean registryMode) {
        this.plugin = plugin;
        this.diagnostics = diagnostics;
        this.storageDir = storageDir;
        this.staging = staging;
        this.registryMode = registryMode;
        if (registryMode) {
            // the registry re-registers from pack sources at every boot, so
            // the last recorded hash is the best guess at what the running
            // server actually holds until the pipeline reports this boot's
            // compile; any world artifact is dead weight — remove it here,
            // worlds are already loaded
            this.activeAtBoot = readMarker();
            this.lastWritten = activeAtBoot;
            removeWorldArtifacts();
            return;
        }
        // worlds load before plugins enable, so the world zip is readable here;
        // if no world exists yet, the side marker is the best available guess
        String world = deployedHash();
        this.activeAtBoot = world != null ? world : readMarker();
        this.lastWritten = activeAtBoot;
    }

    /**
     * The world folder's {@code datapacks/} directory — the one the vanilla
     * pack repository actually scans. Since 26.x, {@code getWorldFolder()}
     * of the first Bukkit world points at the overworld *dimension* folder
     * ({@code world/dimensions/minecraft/overworld}), which vanilla never
     * scans for packs; the world root is the world container + the world's
     * name (its {@code level-name} directory), on every supported version.
     */
    private Path datapacksDir() {
        var server = plugin.getServer();
        if (server.getWorlds().isEmpty()) {
            return null;
        }
        return server.getWorldContainer().toPath()
                .resolve(server.getWorlds().get(0).getName())
                .resolve("datapacks");
    }

    /**
     * Deploys the compiled artifact when the world folder does not already
     * hold exactly these bytes (legacy mode), or tracks the registered
     * content (registry mode). Never throws — a failed write is diagnosed;
     * the running server is untouched.
     */
    public void apply(Path stagedZip, String sha256) {
        if (registryMode) {
            applyRegistryMode(sha256);
            return;
        }
        if (stagedZip == null || sha256 == null || sha256.isEmpty()
                || !Files.isRegularFile(stagedZip)) {
            return;
        }
        String deployed = deployedHash();
        if (sha256.equals(deployed)) {
            lastWritten = sha256;
            return;
        }
        try {
            Path datapacks = datapacksDir();
            if (datapacks == null) {
                throw new IOException("no worlds are loaded");
            }
            Files.createDirectories(datapacks);
            Path temp = datapacks.resolve(ZIP_NAME + ".new");
            Files.copy(stagedZip, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, datapacks.resolve(ZIP_NAME),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, datapacks.resolve(ZIP_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
            removeLegacyDimensionArtifact();
            lastWritten = sha256;
            writeMarker(sha256);
        } catch (IOException | RuntimeException e) {
            diagnostics.publish(Diagnostic.error(
                    DiagnosticCodes.DATAPACK_WRITE_FAILED,
                    "could not deploy the content datapack: " + e.getMessage()));
            return;
        }
        plugin.getLogger().info("MATERIONE-DATAPACK-DEPLOY hash=" + shortHash(sha256)
                + (deployed == null ? " (world folder had none)" : " (replaced " + shortHash(deployed) + ")"));
        if (!sha256.equals(activeAtBoot)) {
            staging.stage(NamespacedId.of("materione", "content-datapack"),
                    "painting/jukebox artifact deployed");
            diagnostics.publish(Diagnostic.warn(
                    DiagnosticCodes.RESTART_STAGED,
                    "painting/jukebox changes were deployed; they take effect at the next restart"));
        }
    }

    /**
     * Registry-mode tracking: the bootstrap registered this boot's content
     * from the same pack sources the pipeline just compiled, so the first
     * apply of a boot records the compiled hash as the registered truth;
     * later applies (reloads) that differ mean the registry is stale until
     * a restart re-registers.
     */
    private void applyRegistryMode(String sha256) {
        if (sha256 == null || sha256.isEmpty()) {
            return;
        }
        if (!bootSnapshotted) {
            bootSnapshotted = true;
            activeAtBoot = sha256;
            lastWritten = sha256;
            writeMarker(sha256);
            return;
        }
        lastWritten = sha256;
        if (!sha256.equals(activeAtBoot)) {
            staging.stage(NamespacedId.of("materione", "registry-content"),
                    "painting/jukebox registry content changed");
            diagnostics.publish(Diagnostic.warn(
                    DiagnosticCodes.RESTART_STAGED,
                    "painting/jukebox registry content changed; a restart re-registers it"));
        }
    }

    /**
     * Removes any content datapack from the world folder — both the correct
     * location and the legacy overworld-dimension one. Registry mode only:
     * a leftover artifact registers its entries at world load and the
     * registry re-registration of the same keys would fail per entry.
     */
    private void removeWorldArtifacts() {
        Path datapacks = datapacksDir();
        if (datapacks != null) {
            Path zip = datapacks.resolve(ZIP_NAME);
            if (Files.isRegularFile(zip)) {
                try {
                    Files.delete(zip);
                    plugin.getLogger().info("MATERIONE-DATAPACK-CLEANUP removed " + zip
                            + " (registry mode needs no world datapack)");
                } catch (IOException e) {
                    diagnostics.publish(Diagnostic.warn(
                            DiagnosticCodes.DATAPACK_WRITE_FAILED,
                            "could not remove the world content datapack: " + e.getMessage()));
                }
            }
        }
        removeLegacyDimensionArtifact();
    }

    /**
     * The pre-26.x-location artifact the applier used to write into the
     * overworld dimension folder (a folder vanilla never scans). Removing it
     * keeps the repair honest — a copy there is dead weight and confusion.
     */
    private void removeLegacyDimensionArtifact() {
        try {
            var server = plugin.getServer();
            if (server.getWorlds().isEmpty()) {
                return;
            }
            Path legacy = server.getWorlds().get(0).getWorldFolder().toPath()
                    .resolve("datapacks").resolve(ZIP_NAME);
            Path current = datapacksDir() == null ? null : datapacksDir().resolve(ZIP_NAME);
            // on pre-26.x versions the two paths coincide — there the legacy
            // location IS the correct one; only remove a true stale artifact
            if (current != null && !legacy.equals(current) && Files.isRegularFile(legacy)) {
                Files.delete(legacy);
                plugin.getLogger().info("MATERIONE-DATAPACK-CLEANUP removed stale " + legacy);
            }
        } catch (IOException | RuntimeException ignored) {
            // fail-soft: a stale copy never breaks deployment
        }
    }

    /** True when the deployed artifact differs from what the running server loaded. */
    public boolean pendingRestartRequired() {
        return !lastWritten.equals(activeAtBoot);
    }

    /**
     * Whether the running server's pack repository actually knows the
     * deployed datapack (via {@link org.bukkit.packs.DataPackManager}). This
     * is the acceptance gate for the whole artifact: a pack present in the
     * folder but absent from the manager was ignored by vanilla — format
     * rejection, wrong location, whatever the reason — and painting/jukebox
     * content silently does not exist. Registry mode has no artifact to
     * accept: registration happened at bootstrap, so the gate always passes.
     */
    public boolean isAcceptedByServer() {
        if (registryMode) {
            return true;
        }
        if (!acceptedKnown) {
            try {
                accepted = plugin.getServer().getDatapackManager().getPacks().stream()
                        .anyMatch(pack -> pack.getName().contains("materione-content"));
            } catch (RuntimeException e) {
                accepted = false; // API unavailable or failed: report honestly
            }
            acceptedKnown = true;
        }
        return accepted;
    }

    private boolean acceptedKnown;
    private boolean accepted;

    /** True when this server registers registry-relevant content natively. */
    public boolean isRegistryMode() {
        return registryMode;
    }

    /** The content hash the bootstrap registered (registry mode), or "none". */
    public String registeredHashOrNone() {
        return lastWritten == null || lastWritten.isEmpty() ? "none" : shortHash(lastWritten);
    }

    /** Doctor view: the hash the world folder holds and the hash this boot loaded. */
    public String deployedHashOrNone() {
        String deployed = deployedHash();
        return deployed == null ? "none" : shortHash(deployed);
    }

    /** The hash the world folder contained at boot, or "none". */
    public String activeAtBootOrNone() {
        return activeAtBoot == null || activeAtBoot.isEmpty() ? "none" : shortHash(activeAtBoot);
    }

    /**
     * The hash embedded in the world folder's deployed datapack, or null when
     * the file is absent or unreadable — the world zip, not the side marker,
     * is the truth about what the server loaded.
     */
    private String deployedHash() {
        Path datapacks = datapacksDir();
        if (datapacks == null) {
            return null;
        }
        Path zip = datapacks.resolve(ZIP_NAME);
        if (!Files.isRegularFile(zip)) {
            return null;
        }
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry marker = file.getEntry("materione.json");
            if (marker == null) {
                return null;
            }
            String json = new String(file.getInputStream(marker).readAllBytes(),
                    StandardCharsets.UTF_8);
            int key = json.indexOf("\"sha256\"");
            if (key < 0) {
                return null;
            }
            int open = json.indexOf('"', json.indexOf(':', key));
            int close = open < 0 ? -1 : json.indexOf('"', open + 1);
            return open < 0 || close < 0 ? null : json.substring(open + 1, close);
        } catch (IOException e) {
            return null;
        }
    }

    private static String shortHash(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "…";
    }

    private String readMarker() {
        try {
            Path file = storageDir.resolve(MARKER);
            return Files.isRegularFile(file)
                    ? Files.readString(file, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void writeMarker(String sha256) {
        try {
            Files.writeString(storageDir.resolve(MARKER), sha256, StandardCharsets.UTF_8);
        } catch (IOException e) {
            diagnostics.publish(Diagnostic.warn(
                    DiagnosticCodes.DATAPACK_WRITE_FAILED,
                    "could not record the deployed datapack hash: " + e.getMessage()));
        }
    }
}
