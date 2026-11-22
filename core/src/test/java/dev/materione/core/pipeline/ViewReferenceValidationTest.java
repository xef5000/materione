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
 * whose viewers are formed by a condition (a HUD) cannot be opened, and naming one is a step rejected at load
 * ({@code MTR-0057}); an id no pack declares is {@code MTR-0105}, its existing meaning; a host that names no view kinds
 * does not judge the reference.
 */
class ViewReferenceValidationTest {

    private static final Map<String, ViewFacet> KINDS = Map.of(
            "gui", new ViewFacet(ViewFacet.ViewerRule.OPENED, ViewFacet.RerenderPolicy.ON_CHANGE, false, false),
            "dialog", new ViewFacet(ViewFacet.ViewerRule.OPENED, ViewFacet.RerenderPolicy.OFF, false, false),
            "hud", new ViewFacet(ViewFacet.ViewerRule.PREDICATE, ViewFacet.RerenderPolicy.ON_CHANGE, false, false));

    /** {@code view.open} names a view: its parameter {@code view} is a reference of kind {@code view}. */
    private static final LogicVocabulary VOCABULARY = key -> "view.open".equals(key)
            ? Optional.of(new LogicVocabulary.Entry(true, Map.of("view", StaticReferences.VIEW), "view"))
            : Optional.empty();

    private static ContentResource resource(String pack, String id, String type, String yaml) {
        return new ContentResource(NamespacedId.of(pack, id), type, Path.of(type + "s/" + id + ".yml"),
                LosslessYaml.parse(yaml));
    }

    private static ContentResource opener(String target) {
        return resource("demo", "menu", "gui",
                "menu:\n  container: chest_3\n  behavior:\n    - on: gui.click\n      do:\n        - view.open: " + target + "\n");
    }

    private static List<Diagnostic> validate(Map<String, ViewFacet> kinds, String target) {
        var walker = new StaticReferenceWalker(VOCABULARY, () -> kinds);
        var packs = List.of(new ContentPack("demo", Path.of("."), null, List.of(
                opener(target),
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
    void aHudNamedByAViewOpenIsAStepRejectedAtLoad() {
        for (String written : List.of("demo:mana", "mana")) {
            List<Diagnostic> found = validate(KINDS, written);

            assertThat(found).as(written).hasSize(1);
            assertThat(found.get(0).code()).isEqualTo(DiagnosticCodes.LOGIC_STEP_REJECTED);
            assertThat(found.get(0).code().code()).isEqualTo("MTR-0057");
            assertThat(found.get(0).message()).contains("hud").contains("shows itself").contains("no verb opens it");
        }
    }

    @Test
    void aHostThatNamesNoViewKindsDoesNotJudgeTheReference() {
        assertThat(validate(Map.of(), "demo:ghost")).isEmpty();
        assertThat(validate(Map.of(), "demo:mana")).isEmpty();
    }
}
