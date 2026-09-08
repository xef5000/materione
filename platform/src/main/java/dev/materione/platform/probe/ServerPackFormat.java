package dev.materione.platform.probe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.bukkit.Bukkit;

/**
 * Reads the running server's own pack formats out of the {@code
 * version.json} shipped at the root of every Paper server jar. This is the
 * ground truth the pack-format table can only approximate: a server newer
 * than this build still reports its own formats, so the advertised pack
 * ranges widen without a table bump (MTR-0018 then stays informational).
 *
 * <p>Three historical shapes of {@code pack_version} exist:
 * <ul>
 *   <li>a plain int on the oldest servers (named the resource format
 *       only);</li>
 *   <li>{@code {"resource": n, "data": n}} ints after the data/resource
 *       split;</li>
 *   <li>{@code {"resource_major": n, "resource_minor": n, "data_major": n,
 *       "data_minor": n}} since 26.x, where formats became major.minor
 *       pairs (e.g. 26.1's data format 101.1).</li>
 * </ul>
 *
 * <p>Detection is best-effort by design: any failure (unexpected launch
 * layout, unreadable jar, schema drift) returns empty and the caller falls
 * back to {@link dev.materione.pack.compile.PackFormats#isKnown}.
 */
public final class ServerPackFormat {

    private ServerPackFormat() {}

    /** The server's two pack formats, as major.minor pairs. */
    public record PackFormats(int resourceMajor, int resourceMinor, int dataMajor, int dataMinor) {}

    /**
     * The server's own pack formats, or empty when they cannot be
     * determined. Locates the server jar through the classloader (works
     * across paperclip layouts — the class's code source is wherever the
     * running server classes actually live).
     */
    public static Optional<PackFormats> detect() {
        return readVersionJson().flatMap(ServerPackFormat::parse);
    }

    private static Optional<String> readVersionJson() {
        try {
            var codeSource = Bukkit.getServer().getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return Optional.empty();
            }
            Path jar = Path.of(codeSource.getLocation().toURI());
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry("version.json");
                if (entry == null) {
                    return Optional.empty();
                }
                try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    StringBuilder json = new StringBuilder();
                    char[] buffer = new char[1024];
                    int read;
                    while ((read = reader.read(buffer)) >= 0) {
                        json.append(buffer, 0, read);
                    }
                    return Optional.of(json.toString());
                }
            }
        } catch (IOException | java.net.URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Pure extraction across all three {@code pack_version} shapes. The
     * plain-int shape named the resource format only — the data half predates
     * it, so it yields a data format of 0/0 (callers treat a 0 major as
     * "unknown").
     */
    static Optional<PackFormats> parse(String versionJson) {
        try {
            JsonElement parsed = JsonParser.parseString(versionJson);
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonElement packVersion = parsed.getAsJsonObject().get("pack_version");
            if (packVersion == null || packVersion.isJsonNull()) {
                return Optional.empty();
            }
            if (packVersion.isJsonObject()) {
                JsonObject object = packVersion.getAsJsonObject();
                int resourceMajor = major(object, "resource_major", "resource");
                int dataMajor = major(object, "data_major", "data");
                if (resourceMajor < 0 && dataMajor < 0) {
                    return Optional.empty(); // an empty or unrecognized object
                }
                return Optional.of(new PackFormats(
                        resourceMajor,
                        minor(object, "resource_minor"),
                        dataMajor,
                        minor(object, "data_minor")));
            }
            if (packVersion.isJsonPrimitive() && packVersion.getAsJsonPrimitive().isNumber()) {
                int resource = packVersion.getAsInt();
                return Optional.of(new PackFormats(resource, 0, 0, 0));
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty(); // malformed json: detection simply fails
        }
    }

    private static int major(JsonObject object, String newName, String legacyName) {
        return intOf(object, newName) >= 0 ? intOf(object, newName) : intOf(object, legacyName);
    }

    private static int minor(JsonObject object, String name) {
        return Math.max(intOf(object, name), 0);
    }

    private static int intOf(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsJsonPrimitive().getAsBigDecimal().intValue() : -1;
    }
}
