package dev.materione.core.pipeline;

import dev.materione.api.pipeline.ReloadScope;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.core.diagnostics.QuarantineTracker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Targeted reload semantics: a FileSet run splices its files onto the last
 * full run; any out-of-scope drift (edit or deletion) expands the scope to
 * everything — never silently missing an edit.
 */
class TargetedReloadTest {

    @TempDir
    Path dir;

    private final List<Diagnostic> published = new java.util.ArrayList<>();

    private final DiagnosticsBus diagnostics = new DiagnosticsBus() {
        @Override
        public void publish(Diagnostic diagnostic) {
            published.add(diagnostic);
        }

        @Override
        public AutoCloseable subscribe(java.util.function.Consumer<Diagnostic> listener) {
            return () -> { };
        }

        @Override
        public List<Diagnostic> recent(int max) {
            return List.of();
        }

        @Override
        public int count(Severity severity) {
            return 0;
        }
    };

    @Test
    void targetedRunSplicesOntoPreviousFullRun() throws IOException {
        Path root = writePacks("alpha:\n  value: 1\n", "beta:\n  value: 2\n");

        PipelineRunner runner = new PipelineRunner(diagnostics, new QuarantineTracker());
        PipelineResult full = runner.request(new ReloadScope.Everything(), root);
        assertThat(full.completed()).isTrue();
        assertThat(resourceCount(runner)).isEqualTo(2);

        // edit only packb's item file, then reload exactly that file
        Files.writeString(root.resolve("packb").resolve("items").resolve("item.yml"), "beta:\n  value: 3\n");
        PipelineResult targeted = runner.request(
                new ReloadScope.FileSet(Set.of(
                        root.resolve("packb").resolve("items").resolve("item.yml"))), root);

        assertThat(targeted.completed()).isTrue();
        assertThat(resourceCount(runner)).isEqualTo(2);
        assertThat(resourceValue(runner, "beta")).isEqualTo("3");
        assertThat(resourceValue(runner, "alpha")).isEqualTo("1"); // spliced from the previous run
    }

    @Test
    void outOfScopeDriftExpandsToEverything() throws IOException {
        Path root = writePacks("alpha:\n  value: 1\n", "beta:\n  value: 2\n");

        PipelineRunner runner = new PipelineRunner(diagnostics, new QuarantineTracker());
        assertThat(runner.request(new ReloadScope.Everything(), root).completed()).isTrue();

        // an edit OUTSIDE the requested scope must not be missed
        Files.writeString(root.resolve("packa").resolve("items").resolve("item.yml"), "alpha:\n  value: 9\n");
        PipelineResult targeted = runner.request(
                new ReloadScope.FileSet(Set.of(
                        root.resolve("packb").resolve("items").resolve("item.yml"))), root);

        assertThat(targeted.completed()).isTrue();
        assertThat(resourceValue(runner, "alpha")).isEqualTo("9"); // expansion picked it up
        assertThat(resourceValue(runner, "beta")).isEqualTo("2");
    }

    @Test
    void targetedRunWithoutPreviousFullRunExpands() throws IOException {
        Path root = writePacks("alpha:\n  value: 1\n", "beta:\n  value: 2\n");
        PipelineRunner runner = new PipelineRunner(diagnostics, new QuarantineTracker());

        PipelineResult targeted = runner.request(new ReloadScope.FileSet(Set.of(
                root.resolve("packa").resolve("items").resolve("item.yml"))), root);

        assertThat(targeted.completed()).isTrue();
        assertThat(resourceCount(runner)).isEqualTo(2); // expanded, not spliced
    }

    @Test
    void deletedOutOfScopeFileExpands() throws IOException {
        Path root = writePacks("alpha:\n  value: 1\n", "beta:\n  value: 2\n");
        PipelineRunner runner = new PipelineRunner(diagnostics, new QuarantineTracker());
        assertThat(runner.request(new ReloadScope.Everything(), root).completed()).isTrue();

        Files.delete(root.resolve("packa").resolve("items").resolve("item.yml"));
        PipelineResult targeted = runner.request(new ReloadScope.FileSet(Set.of(
                root.resolve("packb").resolve("items").resolve("item.yml"))), root);

        assertThat(targeted.completed()).isTrue();
        assertThat(resourceCount(runner)).isEqualTo(1); // deletion is visible
    }

    @Test
    void onePackSplicesOtherPacksOver() throws IOException {
        Path root = writePacks("alpha:\n  value: 1\n", "beta:\n  value: 2\n");
        PipelineRunner runner = new PipelineRunner(diagnostics, new QuarantineTracker());
        assertThat(runner.request(new ReloadScope.Everything(), root).completed()).isTrue();

        Files.writeString(root.resolve("packb").resolve("items").resolve("item.yml"), "beta:\n  value: 7\n");
        PipelineResult targeted = runner.request(new ReloadScope.OnePack("packb"), root);

        assertThat(targeted.completed()).isTrue();
        assertThat(resourceValue(runner, "beta")).isEqualTo("7");
        assertThat(resourceValue(runner, "alpha")).isEqualTo("1");
    }

    // --- helpers -------------------------------------------------------------

    /** Two packs, one content file each; contents from the parameters. */
    private Path writePacks(String alphaYaml, String betaYaml) throws IOException {
        Path root = dir.resolve("packs");
        Files.createDirectories(root.resolve("packa").resolve("items"));
        Files.writeString(root.resolve("packa").resolve("pack.yml"), "id: packa\n");
        Files.writeString(root.resolve("packa").resolve("items").resolve("item.yml"), alphaYaml);
        Files.createDirectories(root.resolve("packb").resolve("items"));
        Files.writeString(root.resolve("packb").resolve("pack.yml"), "id: packb\n");
        Files.writeString(root.resolve("packb").resolve("items").resolve("item.yml"), betaYaml);
        return root;
    }

    private static int resourceCount(PipelineRunner runner) {
        return runner.lastPacks().stream().mapToInt(p -> p.resources().size()).sum();
    }

    private static String resourceValue(PipelineRunner runner, String id) {
        return runner.lastPacks().stream()
                .flatMap(p -> p.resources().stream())
                .filter(r -> r.id().value().equals(id))
                .findFirst()
                .map(r -> String.valueOf(((java.util.Map<?, ?>) r.document().data().get(id)).get("value")))
                .orElse(null);
    }
}
