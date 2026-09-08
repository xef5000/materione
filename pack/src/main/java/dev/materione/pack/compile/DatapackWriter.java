package dev.materione.pack.compile;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.core.diagnostics.DiagnosticCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes the generated world datapack (runtime-systems §10): painting
 * variants, jukebox songs and tags collected from {@code datapackFile()}
 * calls, zipped deterministically to {@code generated/pack/datapack.zip}.
 *
 * <p>The zip carries its content hash twice: the caller compares it against
 * the last artifact it deployed (skip when equal) and the deployed file
 * carries it inside {@code materione.json} so staleness is detectable in
 * the world folder itself (plugin.md §4.4). Datapack changes are
 * restart-required — this writer stages bytes; the platform applies them
 * at boot or stages intent for the next one (§4.5).
 */
public final class DatapackWriter {

    /** Data-pack format of the 1.21.4 Materione floor. */
    public static final int DATA_FORMAT_FLOOR = 61;

    /**
     * Default upper bound for the advertised data-pack range — far above any
     * known version's format. The bound is permissive by design: a server
     * only checks that its own format sits inside {@code [min, max]}, so a
     * high ceiling never rejects a current server, while the floor is what
     * targets the supported range. The server-reported format (when the
     * probe can read it) still wins over this.
     */
    public static final PackFormats.Version DATA_FORMAT_CEILING = new PackFormats.Version(128, 0);

    /** Fixed zip timestamp: same content → same bytes, always. */
    private static final long ZIP_TIME = 1735689600000L; // 2025-01-01T00:00:00Z

    /** The written artifact: staged zip + its content hash. */
    public record Artifact(Path zip, String sha256) {}

    private DatapackWriter() {}

    /**
     * Writes {@code files} ({@code data/...} paths → json) plus the marker
     * entries; null when {@code files} is empty (no artifact needed).
     * Never throws — IO problems surface as a DATAPACK_WRITE_FAILED
     * diagnostic and a null artifact (fail-soft: the resource pack still
     * ships, D6).
     */
    public static Artifact write(
            Map<String, Map<String, Object>> files, Path stagingDir, DiagnosticsBus diagnostics) {
        return write(files, stagingDir, diagnostics, null);
    }

    /** As above, folding the server-reported data-pack format into the upper bound. */
    public static Artifact write(
            Map<String, Map<String, Object>> files, Path stagingDir, DiagnosticsBus diagnostics,
            PackFormats.Version detectedMaxDataFormat) {
        if (files.isEmpty()) {
            return null;
        }
        try {
            var sorted = new TreeMap<String, String>();
            files.forEach((path, json) -> sorted.put(path, PackCompiler.toJsonPublic(json)));
            var canonical = new StringBuilder();
            sorted.forEach((path, json) -> canonical.append(path).append(':').append(json).append('\n'));
            String sha256 = sha256Of(canonical.toString());

            Path dir = stagingDir.resolve("pack");
            Files.createDirectories(dir);
            Path zip = dir.resolve("datapack.zip");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                put(out, "pack.mcmeta", mcmeta(detectedMaxDataFormat));
                put(out, "materione.json", "{\"sha256\":\"" + sha256 + "\"}");
                for (var entry : sorted.entrySet()) {
                    put(out, entry.getKey(), entry.getValue());
                }
            }
            return new Artifact(zip, sha256);
        } catch (IOException | RuntimeException e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.DATAPACK_WRITE_FAILED, Severity.ERROR,
                    "world datapack artifact could not be written: " + e.getMessage(),
                    null, null,
                    "check disk space and the server's write permissions; the resource pack is unaffected"));
            return null;
        }
    }

    /**
     * Both mcmeta schema generations, mirroring the resource pack: {@code
     * pack_format} + {@code supported_formats} (read before the 25w31a
     * schema change) and {@code min_format}/{@code max_format} (read by
     * 1.21.9+/26.x, which ignore {@code supported_formats} — a datapack
     * advertising only the old fields shows as incompatible on current
     * servers and its painting/jukebox content silently never loads).
     */
    private static String mcmeta(PackFormats.Version detectedMaxDataFormat) {
        PackFormats.Version max = detectedMaxDataFormat != null
                && detectedMaxDataFormat.major() > DATA_FORMAT_CEILING.major()
                ? detectedMaxDataFormat : DATA_FORMAT_CEILING;
        return "{\"pack\":{\"description\":\"Materione content\",\"pack_format\":"
                + DATA_FORMAT_FLOOR + ",\"supported_formats\":[" + DATA_FORMAT_FLOOR + ","
                + max.major() + "],\"min_format\":" + DATA_FORMAT_FLOOR
                + ",\"max_format\":" + max.toMcmeta() + "}}";
    }

    private static void put(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(entry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(ZIP_TIME);
        return entry;
    }

    private static String sha256Of(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(content.hashCode());
        }
    }
}
