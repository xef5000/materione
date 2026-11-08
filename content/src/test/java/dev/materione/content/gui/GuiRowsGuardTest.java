package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The guard of gui.md section 12.10 row M: no GUI file in the repository says {@code rows:} any more. A GUI names its
 * container ({@code container: chest_3}); {@code rows:} was dropped outright, with no alias (owner decision, section
 * 12.13 item 9), so a file that still has it would be a GUI the parser quarantines for a missing container.
 *
 * <p>A GUI file is one whose top-level entry (indented two spaces under its id) has {@code rows:} next to
 * {@code title:} or {@code layers:}, or any file under a {@code guis/} directory. Glyph sheets and items also say
 * {@code rows}, but not under those keys, and stay as they are.
 */
class GuiRowsGuardTest {

    private static final Pattern ROWS_KEY = Pattern.compile("(?m)^ {2}rows:");
    private static final Pattern GUI_SIGNATURE = Pattern.compile("(?m)^ {2}(title|layers):");

    /** The directories whose YAML the repository ships as content: fixtures, the built-in packs and the harness. */
    private static final List<String> SCANNED = List.of("fixtures", "plugin/src/main/resources", "harness/protocol");

    @Test
    void noGuiFileInTheRepositoryStillSaysRows() throws IOException {
        Path repo = GoldenFiles.fixturesRoot().getParent();
        var offenders = new java.util.ArrayList<String>();
        for (String dir : SCANNED) {
            Path root = repo.resolve(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".yml")).toList()) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    boolean inGuiDirectory = file.toString().replace('\\', '/').contains("/guis/");
                    boolean guiShaped = GUI_SIGNATURE.matcher(text).find();
                    if (ROWS_KEY.matcher(text).find() && (inGuiDirectory || guiShaped)) {
                        offenders.add(repo.relativize(file).toString().replace('\\', '/'));
                    }
                }
            }
        }
        assertThat(offenders)
                .as("GUI files that still say rows: (write container: chest_N instead, gui.md section 12.4)")
                .isEmpty();
    }
}
