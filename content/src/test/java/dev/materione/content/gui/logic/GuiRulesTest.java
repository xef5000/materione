package dev.materione.content.gui.logic;

import static dev.materione.content.gui.GuiTestSupport.definition;
import static dev.materione.content.gui.GuiTestSupport.layer;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.content.gui.GuiDefinition;
import dev.materione.content.gui.GuiTestSupport;
import dev.materione.content.gui.GuiType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A GUI carries {@code behavior:} and {@code flows:} like an item does: read losslessly, described by
 * the gui type, and checked in the diagnose stage through the host's rule checker (logic.md §6, §10).
 */
class GuiRulesTest {

    private static Map<String, Object> rule(String on, String... steps) {
        var rule = new LinkedHashMap<String, Object>();
        rule.put("on", on);
        rule.put("do", List.of((Object[]) steps));
        return rule;
    }

    @Test
    void aGuiWithoutLogicHasEmptyRules() {
        GuiDefinition gui = GuiTestSupport.parse(GuiTestSupport.type(), definition(1));

        assertThat(gui.rules()).isSameAs(GuiRules.EMPTY);
        assertThat(gui.rules().isEmpty()).isTrue();
    }

    @Test
    void behaviorAndFlowsAreReadAsAuthored() {
        var raw = definition(1, layer("b", "button", "x", 0, "y", 0, "width", 8, "height", 8));
        raw.put("behavior", List.of(rule("gui.click", "gui.close"), "not a mapping"));
        raw.put("flows", Map.of("greet", List.of("gui.refresh")));

        GuiRules rules = GuiTestSupport.parse(GuiTestSupport.type(), raw).rules();

        assertThat(rules.behavior()).hasSize(2);
        assertThat(rules.behavior().get(0)).isEqualTo(rule("gui.click", "gui.close"));
        assertThat(rules.behavior().get(1)).as("an entry that is not a mapping keeps its index").isEqualTo("not a mapping");
        assertThat(rules.flows()).isEqualTo(Map.of("greet", List.of("gui.refresh")));
        assertThat(rules.problems()).isEmpty();
    }

    @Test
    void aBareOnKeyThatYamlReadAsABooleanIsRescued() {
        var yamlRule = new LinkedHashMap<Object, Object>();
        yamlRule.put(Boolean.TRUE, "gui.open"); // YAML 1.1 reads an unquoted `on:` this way
        yamlRule.put("do", List.of("gui.close"));
        var raw = definition(1);
        raw.put("behavior", List.of(yamlRule));

        GuiRules rules = GuiTestSupport.parse(GuiTestSupport.type(), raw).rules();

        assertThat(rules.behavior().get(0)).isEqualTo(rule("gui.open", "gui.close"));
    }

    @Test
    void sectionsOfTheWrongShapeAreProblemsNotCrashes() {
        var raw = definition(1);
        raw.put("behavior", "gui.click");
        raw.put("flows", List.of("nope"));

        GuiRules rules = GuiTestSupport.parse(GuiTestSupport.type(), raw).rules();

        assertThat(rules.behavior()).isEmpty();
        assertThat(rules.flows()).isNull();
        assertThat(rules.problems()).extracting(GuiRules.Problem::field).containsExactly("behavior", "flows");
    }

    @Test
    void theGuiTypeReportsShapeProblemsAtTheSectionAndStillCompiles() {
        var raw = definition(1);
        raw.put("behavior", "gui.click");
        GuiType type = GuiTestSupport.type();

        List<Diagnostic> diagnostics = type.diagnose(GuiTestSupport.parse(type, raw));

        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code()).isEqualTo(DiagnosticCodes.BEHAVIOR_RULE_INVALID);
        assertThat(diagnostics.get(0).severity()).isEqualTo(Severity.WARN);
        assertThat(diagnostics.get(0).target().field()).containsExactly("behavior");
    }

    @Test
    void theRuleCheckerRunsOnlyForGuisThatHaveRulesAndItsFindingsAreReported() {
        var calls = new ArrayList<String>();
        GuiType type = new GuiType(GuiTestSupport.components(), gui -> {
            calls.add(gui.id().toString());
            return List.of(new Diagnostic(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                    "rule broke", gui.id(), null, null,
                    dev.materione.api.diagnostics.DiagnosticTarget.field("behavior", "0", "do", "1")));
        });

        assertThat(type.diagnose(GuiTestSupport.parse(type, definition(1)))).isEmpty();
        assertThat(calls).isEmpty();

        var raw = definition(1);
        raw.put("behavior", List.of(rule("gui.click", "gui.close")));
        List<Diagnostic> diagnostics = type.diagnose(GuiTestSupport.parse(type, raw));

        assertThat(calls).containsExactly("shop:test");
        assertThat(diagnostics).extracting(Diagnostic::message).containsExactly("rule broke");
        assertThat(diagnostics.get(0).target().field()).containsExactly("behavior", "0", "do", "1");
    }

    @Test
    void theGuiTypeDescribesItsLogicSections() {
        List<String> parameters = GuiTestSupport.type().describe().parameters().stream()
                .map(ParameterSchema::id).toList();

        assertThat(parameters).contains("behavior", "flows");
    }
}
