package dev.materione.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Repo-wide source hygiene: every text file is valid UTF-8 and free of
 * mojibake. Editors and tools that read UTF-8 bytes back as CP-1252 silently
 * corrupt prose (an em dash becomes {@code \u00C3\u00A2\u20AC\u201D}); the
 * damage spreads through comments, UI labels and diagnostics text where it is
 * easy to miss in review. This test fails the build instead.
 *
 * <p>Detection is by construction, not by heuristics: the sentinel code points
 * below are the CP-1252 renderings of UTF-8 lead bytes. None of them occurs in
 * legitimate Materione content, so any occurrence is corruption.
 */
class SourceEncodingTest {

    /** Directories that are not our source (vendored, generated, or tooling). */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", ".claude", ".commandcode", ".zcode",
            "build", "node_modules", "dist", "out", "nexo-reference", "blockbench");

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "ico", "webp", "jar", "zip", "gz", "class",
            "ttf", "otf", "woff", "woff2", "bin", "ogg", "wav", "pdf", "nbt", "dat");

    /**
     * CP-1252 renderings of UTF-8 lead bytes (\u00C2, \u00C3, \u00E2, \u00F0)
     * plus the replacement character. C1 controls are caught separately.
     */
    private static final char[] MOJIBAKE_SENTINELS = {
        0x00C2, 0x00C3, 0x00E2, 0x00F0, 0xFFFD
    };

    @Test
    void everySourceFileIsCleanUtf8() throws IOException {
        Path root = Path.of("..").toRealPath();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(root)) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> !isSkipped(root, path))
                    .forEach(path -> inspect(root, path, problems));
        }
        assertThat(problems)
                .as("files with encoding corruption (see SourceEncodingTest)")
                .isEmpty();
    }

    private static void inspect(Path root, Path file, List<String> problems) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return; // unreadable files are not this test's business
        }
        String relative = root.relativize(file).toString().replace('\\', '/');
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            problems.add(relative + ": not valid UTF-8");
            return;
        }
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String reason = corruptionIn(lines[index]);
            if (reason != null) {
                problems.add(relative + ":" + (index + 1) + ": " + reason);
            }
        }
    }

    private static String corruptionIn(String line) {
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            for (char sentinel : MOJIBAKE_SENTINELS) {
                if (character == sentinel) {
                    return "mojibake (U+"
                            + Integer.toHexString(character).toUpperCase(Locale.ROOT) + ")";
                }
            }
            if (character >= 0x0080 && character <= 0x009F) {
                return "stray C1 control (U+"
                        + Integer.toHexString(character).toUpperCase(Locale.ROOT) + ")";
            }
        }
        return null;
    }

    private static boolean isSkipped(Path root, Path file) {
        for (Path segment : root.relativize(file)) {
            if (SKIPPED_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && BINARY_EXTENSIONS.contains(name.substring(dot + 1));
    }
}
