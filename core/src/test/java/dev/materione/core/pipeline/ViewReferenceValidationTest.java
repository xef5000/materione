package dev.materione.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.ViewFacet;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.pipeline.validate.LogicVocabulary;
import dev.materione.core.pipeline.validate.ReferenceValidator;
import dev.materione.core.pipeline.validate.StaticReferenceWalker;
import dev.materione.core.pipeline.validate.StaticReferences;
import dev.materione.core.pipeline.validate.ValidationEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A {@code view.open} that names a view by a static id (views.md sections 3.6, 9): the id must be a definition of a view
 * kind, which the host's content types say through their view facet, and that kind must be one a verb opens. A kind
 * whose viewers are formed by a condition (a HUD) cannot be opened, and naming one in a {@code view.open} is
 * {@code MTR-0126} (the step is kept and fails when it runs); an id no pack declares is {@code MTR-0105}, its existing meaning; a host that names no view kinds
 * does not judge the reference.
 */
class ViewReferenceValidationTest {

    private static final Map<String, ViewFacet> KINDS = Map.of(
            "gui", new ViewFacet(ViewFacet.ViewerRule.OPENED, ViewFacet.RerenderPolicy.ON_CHANGE, false, false),
            "dialog", new ViewFacet(ViewFacet.ViewerRule.OPENED, ViewFacet.RerenderPolicy.OFF, false, false),
            "hud", new ViewFacet(ViewFacet.ViewerRule.PREDICATE, ViewFacet.RerenderPolicy.ON_CHANGE, false, false));

    /** {@code view.open}, {@code view.close}, {@code view.refresh} and {@code in_view} name a view: their {@code view} is a reference of kind {@code view}. */
    private static final LogicVocabulary VOCABULARY = key -> java.util.Set.of("view.open", "view.close", "view.refresh", "in_view").contains(key)
            ? Optional.of(new LogicVocabulary.Entry(true, Map.of("view", StaticReferences.VIEW), "view"))
            : Optional.empty();

    private static ContentResource resource(String pack, String id, String type, String yaml) {
        return new ContentResource(NamespacedId.of(pack, id), type, Path.of(type + "s/" + id + ".yml"),
                LosslessYaml.parse(yaml));
    }

    private static ContentResource opener(String verb, String target) {
        return resource("demo", "menu", "gui",
                "menu:\n  container: chest_3\n  behavior:\n    - on: gui.click\n      do:\n        - " + verb + ": " + target
                        + "\n");
    }

    private static List<Diagnostic> validate(Map<String, ViewFacet> kinds, String target) {
        return validate(kinds, "view.open", target);
    }

    private static List<Diagnostic> validate(Map<String, ViewFacet> kinds, String verb, String target) {
        var walker = new StaticReferenceWalker(VOCABULARY, () -> kinds);
        var packs = List.of(new ContentPack("demo", Path.of("."), null, List.of(
                opener(verb, target),
                resource("demo", "shop", "gui", "shop:\n  container: chest_3\n"),
                resource("demo", "ask", "dialog", "ask:\n  title: Ask\n"),
                resource("demo", "mana", "hud", "mana:\n  layers: []\n"))));
        var environment = new ValidationEnvironment(packs, java.util.Set.of(), Map.of(), walker);
        return new ReferenceValidator().validate(packs.get(0).resources().get(0), environment);
    }

    @Test
    void aGuiAndADialogAreOpenableByQualifiedOrBareName() {
        assertThat(validate(KINDS, "demo:shop")).isEmpty();
        assertThat(validate(KINDS, "shop")).isEmpty();
        assertThat(validate(KINDS, "demo:ask")).isEmpty();
        assertThat(validate(KINDS, "ask")).isEmpty();
    }

    @Test
    void anIdNoPackDeclaresIsTheUnresolvedReferenceWarning() {
        List<Diagnostic> found = validate(KINDS, "demo:ghost");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).code()).isEqualTo(DiagnosticCodes.RULE_STEP_REFERENCE_UNRESOLVED);
        assertThat(found.get(0).message()).contains("view 'demo:ghost'").contains("no pack declares");
    }

    @Test
    void aHudNamedByAViewOpenIsTheNotOpenableWarning() {
        for (String written : List.of("demo:mana", "mana")) {
            List<Diagnostic> found = validate(KINDS, written);

            assertThat(found).as(written).hasSize(1);
            assertThat(found.get(0).code()).isEqualTo(DiagnosticCodes.VIEW_NOT_OPENABLE);
            assertThat(found.get(0).code().code()).isEqualTo("MTR-0126");
            assertThat(found.get(0).message()).contains("hud").contains("shows itself").contains("no verb opens it");
        }
    }

    @Test
    void aViewCloseNamingAHudIsNotJudgedOpenable() {
        assertThat(validate(KINDS, "view.close", "demo:mana")).isEmpty();
        assertThat(validate(KINDS, "view.close", "demo:ghost")).extracting(Diagnostic::code)
                .containsExactly(DiagnosticCodes.RULE_STEP_REFERENCE_UNRESOLVED);
    }

    private static List<Diagnostic> validateKind(String view, String kind, List<ContentResource> definitions) {
        var walker = new StaticReferenceWalker(VOCABULARY, () -> KINDS);
        var resources = new java.util.ArrayList<ContentResource>();
        resources.add(resource("demo", "menu", "gui",
                "menu:\n  container: chest_3\n  behavior:\n    - on: gui.click\n      do:\n        - view.open: {view: "
                        + view + ", kind: " + kind + "}\n"));
        resources.addAll(definitions);
        var packs = List.of(new ContentPack("demo", Path.of("."), null, resources));
        var environment = new ValidationEnvironment(packs, java.util.Set.of(), Map.of(), walker);
        return new ReferenceValidator().validate(resources.get(0), environment);
    }

    @Test
    void theStepsKindNarrowsWhichViewsTheIdNames() {
        var sharedGuiAndHud = List.of(
                resource("demo", "x", "gui", "x:\n  container: chest_3\n"),
                resource("demo", "x", "hud", "x:\n  layers: []\n"));
        var onlyHud = List.of(resource("demo", "x", "hud", "x:\n  layers: []\n"));

        assertThat(validateKind("x", "hud", sharedGuiAndHud)).extracting(Diagnostic::code)
                .containsExactly(DiagnosticCodes.VIEW_NOT_OPENABLE);
        assertThat(validateKind("x", "gui", sharedGuiAndHud)).isEmpty();
        assertThat(validateKind("x", "gui", onlyHud)).extracting(Diagnostic::code)
                .containsExactly(DiagnosticCodes.RULE_STEP_REFERENCE_UNRESOLVED);
        assertThat(validateKind("x", "banana", sharedGuiAndHud)).extracting(Diagnostic::code)
                .containsExactly(DiagnosticCodes.RULE_STEP_REFERENCE_UNRESOLVED);
    }

    @Test
    void viewRefreshAndInViewNameAnyViewKindAndAreNotJudgedOpenable() {
        for (String verb : List.of("view.refresh", "in_view")) {
            assertThat(validate(KINDS, verb, "demo:shop")).as(verb).isEmpty();
            assertThat(validate(KINDS, verb, "ask")).as(verb).isEmpty();
            assertThat(validate(KINDS, verb, "demo:mana")).as(verb + " of a HUD refreshes its viewers").isEmpty();
            assertThat(validate(KINDS, verb, "demo:ghost")).as(verb).extracting(Diagnostic::code)
                    .containsExactly(DiagnosticCodes.RULE_STEP_REFERENCE_UNRESOLVED);
        }
    }

    @Test
    void aHostThatNamesNoViewKindsDoesNotJudgeTheReference() {
        assertThat(validate(Map.of(), "demo:ghost")).isEmpty();
        assertThat(validate(Map.of(), "demo:mana")).isEmpty();
    }
}
