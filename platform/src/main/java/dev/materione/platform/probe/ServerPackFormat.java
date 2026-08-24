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
 * Reads the running server's own resource-pack format out of the {@code
 * version.json} shipped at the root of every Paper server jar ({@code
 * pack_version} — an int on older servers, {@code {"resource": …,
 * "data": …}} since the data/resource split). This is the ground truth the
 * pack-format table can only approximate: a server newer than this build
 * still reports its own format, so the advertised pack range widens
 * without a table bump (MTR-0018 then stays informational).
 *
 * <p>Detection is best-effort by design: any failure (unexpected launch
 * layout, unreadable jar, schema drift) returns empty and the caller falls
 * back to {@link dev.materione.pack.compile.PackFormats#isKnown}.
 */
public final class ServerPackFormat {

    private ServerPackFormat() {}

    /**
     * The server's resource-pack format, or empty when it cannot be
     * determined. Locates the server jar through the classloader (works
     * across paperclip layouts — the class's code source is wherever the
     * running server classes actually live).
     */
    public static Optional<Integer> detectResourceFormat() {
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
                    return parseResourceFormat(json.toString());
                }
            }
        } catch (IOException | java.net.URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Pure extraction of {@code pack_version}'s resource half — both the
     * plain-int shape ({@code "pack_version": 46}) and the split object
     * shape ({@code "pack_version": {"resource": 46, "data": 61}}).
     */
    static Optional<Integer> parseResourceFormat(String versionJson) {
        try {
            JsonElement parsed = JsonParser.parseString(versionJson);
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonElement packVersion = root.get("pack_version");
            if (packVersion == null || packVersion.isJsonNull()) {
                return Optional.empty();
            }
            if (packVersion.isJsonObject()) {
                JsonElement resource = packVersion.getAsJsonObject().get("resource");
                if (resource != null && resource.isJsonPrimitive()
                        && resource.getAsJsonPrimitive().isNumber()) {
                    return Optional.of(resource.getAsInt());
                }
                return Optional.empty();
            }
            if (packVersion.isJsonPrimitive() && packVersion.getAsJsonPrimitive().isNumber()) {
                return Optional.of(packVersion.getAsInt());
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty(); // malformed json: detection simply fails
        }
    }
}
