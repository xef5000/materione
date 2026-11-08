package dev.materione.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticTarget;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Items as templates through the pipeline (plugin.md section 3.1, #203): a link to an item is a valid
 * template reference for an item and for nothing else, a loop is MTR-0093 for every resource that meets
 * it and for no other, a shared template/item id warns with MTR-0094 and uses the template, and a
 * targeted reload of the parent file re-validates the child.
 */
class ItemAsTemplatePipelineTest {

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(512);
    private final QuarantineTracker quarantine = new QuarantineTracker();
    private final PipelineRunner runner = new PipelineRunner(diagnostics, quarantine);

    @TempDir
    Path packsRoot;

    private Path pack;

    @BeforeEach
    void pack() throws IOException {
        pack = packsRoot.resolve("demo");
        Files.createDirectories(pack.resolve("items"));
        Files.createDirectories(pack.resolve("templates"));
        Files.createDirectories(pack.resolve("blocks"));
        Files.writeString(pack.resolve("pack.yml"), "id: demo\nversion: 1.0.0\nschema_version: 1\n");
    }

    private void write(String path, String yaml) throws IOException {
        Path file = pack.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    private PipelineResult run() {
        return runner.request(new ReloadScope.Everything(), packsRoot);
    }

    private List<NamespacedId> quarantined() {
        return quarantine.all().stream().map(QuarantineTracker.Quarantined::resource).distinct().toList();
    }

    private Diagnostic reasonFor(String id) {
        return quarantine.all().stream()
                .filter(entry -> entry.resource().equals(NamespacedId.parse(id)))
                .findFirst().orElseThrow().reason();
    }

    private List<Diagnostic> published(dev.materione.api.diagnostics.DiagnosticCode code) {
        return diagnostics.recent(512).stream().filter(d -> d.code().equals(code)).toList();
    }

    @Test
    void anItemNamingAnotherItemAsItsTemplateIsValidAndGetsATemplateEdge() throws IOException {
        write("items/ruby_sword.yml", "ruby_sword:\n  material: DIAMOND_SWORD\n");
        write("items/ruby_sword_plus.yml", "ruby_sword_plus:\n  template: ruby_sword\n");

        assertThat(run().completed()).isTrue();

        assertThat(quarantine.size()).isZero();
        assertThat(published(DiagnosticCodes.TEMPLATE_AMBIGUOUS)).isEmpty();
        assertThat(runner.lastGraph().edges()).anySatisfy(edge -> {
            assertThat(edge.from().toString()).isEqualTo("demo:ruby_sword_plus");
            assertThat(edge.to().toString()).isEqualTo("demo:ruby_sword");
        });
    }

    @Test
    void aChainThroughItemsAndATemplateResolves() throws IOException {
        write("templates/base.yml", "base:\n  material: PAPER\n");
        write("items/a.yml", "a:\n  template: base\n");
        write("items/b.yml", "b:\n  template: a\n");
        write("items/c.yml", "c:\n  template: b\n");

        assertThat(run().completed()).isTrue();

        assertThat(quarantine.size()).isZero();
    }

    @Test
    void aBlockCannotBeBasedOnAnItem() throws IOException {
        write("items/ruby_sword.yml", "ruby_sword:\n  material: DIAMOND_SWORD\n");
        write("blocks/lamp.yml", "lamp:\n  template: ruby_sword\n");

        assertThat(run().completed()).isTrue();

        assertThat(quarantined()).containsExactly(NamespacedId.parse("demo:lamp"));
        Diagnostic reason = reasonFor("demo:lamp");
        assertThat(reason.code()).isEqualTo(DiagnosticCodes.UNRESOLVED_REFERENCE);
        assertThat(reason.message()).contains("is an item").contains("only an item can be based on an item");
        assertThat(reason.target()).isEqualTo(DiagnosticTarget.field("template"));
    }

    @Test
    void aTemplateCannotBeBasedOnAnItem() throws IOException {
        write("items/ruby_sword.yml", "ruby_sword:\n  material: DIAMOND_SWORD\n");
        write("templates/derived.yml", "derived:\n  template: ruby_sword\n  lore: [x]\n");

        assertThat(run().completed()).isTrue();

        assertThat(quarantined()).containsExactly(NamespacedId.parse("demo:derived"));
        assertThat(reasonFor("demo:derived").message()).contains("is an item");
    }

    @Test
    void aLoopQuarantinesEveryResourceThatMeetsItWithMtr0093AndNothingElse() throws IOException {
        write("items/a.yml", "a:\n  template: b\n  material: PAPER\n");
        write("items/b.yml", "b:\n  template: a\n");
        write("items/downstream.yml", "downstream:\n  template: a\n");
        write("items/innocent.yml", "innocent:\n  material: STICK\n");
        write("items/self.yml", "self:\n  template: self\n");

        assertThat(run().completed()).isTrue();

        assertThat(quarantined()).containsExactlyInAnyOrder(
                NamespacedId.parse("demo:a"), NamespacedId.parse("demo:b"),
                NamespacedId.parse("demo:downstream"), NamespacedId.parse("demo:self"));
        Diagnostic loop = reasonFor("demo:a");
        assertThat(loop.code()).isEqualTo(DiagnosticCodes.TEMPLATE_CYCLE);
        assertThat(loop.code().code()).isEqualTo("MTR-0093");
        assertThat(loop.severity()).isEqualTo(Severity.ERROR);
        assertThat(loop.message()).contains("demo:b -> demo:a -> demo:b");
        assertThat(loop.target()).isEqualTo(DiagnosticTarget.field("template"));
        assertThat(reasonFor("demo:self").code()).isEqualTo(DiagnosticCodes.TEMPLATE_CYCLE);
        assertThat(runner.lastGraph().node(NamespacedId.parse("demo:innocent"))).isPresent();
    }

    @Test
    void aTemplateLoopIsTheSameStableDiagnostic() throws IOException {
        write("templates/x.yml", "x:\n  template: y\n  material: PAPER\n");
        write("templates/y.yml", "y:\n  template: x\n");
        write("items/uses_x.yml", "uses_x:\n  template: x\n");

        assertThat(run().completed()).isTrue();

        assertThat(reasonFor("demo:uses_x").code()).isEqualTo(DiagnosticCodes.TEMPLATE_CYCLE);
        assertThat(reasonFor("demo:x").code()).isEqualTo(DiagnosticCodes.TEMPLATE_CYCLE);
    }

    @Test
    void aSharedTemplateAndItemIdUsesTheTemplateAndWarnsWithoutQuarantine() throws IOException {
        write("templates/blade.yml", "blade:\n  material: PAPER\n");
        write("items/blade.yml", "blade:\n  material: DIAMOND_SWORD\n");
        write("items/child.yml", "child:\n  template: blade\n");

        assertThat(run().completed()).isTrue();

        assertThat(quarantine.size()).isZero();
        List<Diagnostic> warnings = published(DiagnosticCodes.TEMPLATE_AMBIGUOUS);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).severity()).isEqualTo(Severity.WARN);
        assertThat(warnings.get(0).resource()).isEqualTo(NamespacedId.parse("demo:child"));
        assertThat(warnings.get(0).message()).contains("demo:blade").contains("the template is used");
    }

    @Test
    void aSharedIdIsNotAmbiguousForAChildThatCannotUseItems() throws IOException {
        write("templates/blade.yml", "blade:\n  technique: note_block\n");
        write("items/blade.yml", "blade:\n  material: DIAMOND_SWORD\n");
        write("blocks/lamp.yml", "lamp:\n  template: blade\n");

        assertThat(run().completed()).isTrue();

        assertThat(published(DiagnosticCodes.TEMPLATE_AMBIGUOUS)).isEmpty();
        assertThat(quarantine.size()).isZero();
    }

    @Test
    void anUnknownGrandparentQuarantinesTheChildWithoutACrash() throws IOException {
        write("items/mid.yml", "mid:\n  template: ghost\n");
        write("items/leaf.yml", "leaf:\n  template: mid\n");

        assertThat(run().completed()).isTrue();

        assertThat(reasonFor("demo:leaf").code()).isEqualTo(DiagnosticCodes.UNRESOLVED_REFERENCE);
        assertThat(reasonFor("demo:leaf").message()).contains("demo:ghost");
        assertThat(reasonFor("demo:mid").message()).isEqualTo("unresolved template 'ghost'");
    }

    @Test
    void aTargetedReloadOfTheParentFileRevalidatesTheChild() throws IOException {
        write("items/ruby_sword.yml", "ruby_sword:\n  material: DIAMOND_SWORD\n");
        write("items/ruby_sword_plus.yml", "ruby_sword_plus:\n  template: ruby_sword\n");
        runner.replaceFingerprints(FingerprintCache.empty());
        assertThat(run().completed()).isTrue();
        assertThat(quarantine.size()).isZero();

        // the parent goes away: the child, in a file the scope does not name, must notice
        Path parent = pack.resolve("items/ruby_sword.yml");
        Files.delete(parent);
        assertThat(runner.request(new ReloadScope.FileSet(Set.of(parent)), packsRoot).completed()).isTrue();
        assertThat(quarantined()).containsExactly(NamespacedId.parse("demo:ruby_sword_plus"));

        // and recover the moment the parent is back
        Files.writeString(parent, "ruby_sword:\n  material: DIAMOND_SWORD\n");
        assertThat(runner.request(new ReloadScope.FileSet(Set.of(parent)), packsRoot).completed()).isTrue();
        assertThat(quarantine.size()).isZero();
    }
}
