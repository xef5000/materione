package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.materione.plugin.logic.planted.PlantedAdapter;
import dev.materione.plugin.logic.planted.PlantedFirst;
import dev.materione.plugin.logic.planted.PlantedListener;
import dev.materione.plugin.logic.planted.PlantedSecond;
import dev.materione.testing.arch.MaterioneArchRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The build's teeth for logic.md §9: no main-source class over 400 lines in the logic packages, and
 * one action never depends on another (R11), and a listener never reaches an action or the renderer
 * directly (R10). Each guard is proven against a planted violation so a rule that can no longer fail
 * is itself a test failure.
 */
class LogicCodeOrganisationTest {

    /** The packages logic.md §9 puts under the size guard that exist today (source roots, module-relative). */
    private static final List<Path> GUARDED = List.of(
            Path.of("..", "api", "src", "main", "java", "dev", "materione", "api", "logic"),
            Path.of("..", "behavior", "src", "main", "java", "dev", "materione", "behavior", "logic"),
            Path.of("..", "behavior", "src", "main", "java", "dev", "materione", "behavior", "flow"),
            Path.of("..", "behavior", "src", "main", "java", "dev", "materione", "behavior", "run"),
            Path.of("src", "main", "java", "dev", "materione", "plugin", "logic"),
            Path.of("src", "main", "java", "dev", "materione", "plugin", "gui"),
            Path.of("..", "content", "src", "main", "java", "dev", "materione", "content", "gui", "logic"));

    private static final int MAX_LINES = 400;

    @Test
    void noLogicClassExceedsTheSizeGuard() throws IOException {
        for (Path root : GUARDED) {
            assertThat(root).as("guarded source root %s must exist", root).isDirectory();
            assertThat(oversized(root)).as("classes over %d lines under %s", MAX_LINES, root).isEmpty();
        }
    }

    @Test
    void theSizeGuardFailsOnAnOversizedClass(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Small.java"), "class Small {}\n");
        Files.writeString(dir.resolve("Huge.java"), "class Huge {\n" + "    int x;\n".repeat(MAX_LINES) + "}\n");

        assertThat(oversized(dir)).containsExactly("Huge.java");
    }

    @Test
    void actionClassesAreIndependentOfEachOther() {
        // the real rule runs over the whole jar in AllModulesArchitectureTest; this proves it can fail
        var planted = new ClassFileImporter().importClasses(PlantedFirst.class, PlantedSecond.class,
                PlantedFirst.Params.class);
        var rule = MaterioneArchRules.actionsAreIndependentIn("dev.materione.plugin.logic.planted");

        assertThatThrownBy(() -> rule.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedSecond depends on action class")
                .hasMessageContaining("PlantedFirst");
        // a class and its own nested parameter record are one unit
        rule.check(new ClassFileImporter().importClasses(PlantedFirst.class, PlantedFirst.Params.class));
    }

    @Test
    void listenersAreAdaptersRuleFailsWhenAListenerReachesAnActionDirectly() {
        // the real rule runs over the whole jar in AllModulesArchitectureTest; this proves it can fail
        var planted = new ClassFileImporter().importClasses(PlantedListener.class, PlantedAdapter.class,
                PlantedFirst.class, PlantedFirst.Params.class);
        var rule = MaterioneArchRules.listenersAreAdaptersIn(
                List.of("dev.materione.plugin.logic.planted"), List.of("dev.materione.plugin.logic.planted"));

        assertThatThrownBy(() -> rule.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedListener is a listener and depends on")
                .hasMessageContaining("PlantedFirst")
                .hasMessageNotContaining("PlantedAdapter is a listener");
        // a listener that only hands the event on is the shape the rule wants
        rule.check(new ClassFileImporter().importClasses(PlantedAdapter.class));
    }

    private static List<String> oversized(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> lineCount(path) > MAX_LINES)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static long lineCount(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
