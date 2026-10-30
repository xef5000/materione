package dev.materione.plugin.gui.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.plugin.gui.GuiFixture;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A GUI's rules through the flow compiler (logic.md §5.1, §10): indexed by trigger and layer for the
 * runtime, and reported with step paths for {@code gui.validate} and the pack's diagnostics.
 */
class GuiRulesCompileTest {

    private GuiFixture f;
    private GuiRuleDiagnoser diagnoser;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        diagnoser = new GuiRuleDiagnoser(f.logic::lookup);
    }

    private List<Diagnostic> check(String yaml) {
        return diagnoser.check(f.definition(yaml));
    }

    private static final String HEAD = """
            shop:
              title: Shop
              rows: 2
              layers:
                - id: buy
                  type: button
                  x: 8
                  y: 18
                  width: 16
                  height: 16
                - id: ghost
                  type: button
                  x: 26
                  y: 18
                  width: 16
                  height: 16
                  visible: false
              behavior:
            """;

    @Test
    void aCleanGuiHasNoProblems() {
        assertThat(check(HEAD + """
                    - on: gui.click
                      layer: buy
                      click: [left, right]
                      slot: 0
                      do:
                        - gui.close
                    - on: gui.open
                      do:
                        - gui.refresh
                """)).isEmpty();
    }

    @Test
    void theRuleSetIndexesRulesByTriggerAndLayer() {
        f.gui(HEAD + """
                    - on: gui.click
                      layer: buy
                      do:
                        - gui.close
                    - on: gui.click
                      layer: [buy, ghost]
                      click: left
                      do:
                        - gui.refresh
                    - on: gui.click
                      do:
                        - gui.refresh
                    - on: gui.open
                      do:
                        - gui.refresh
                    - on: gui.tick
                      every: 40
                      do:
                        - gui.refresh
                    - on: gui.tick
                      every: 60
                      do:
                        - gui.refresh
                """);
        GuiRuleSet rules = f.guis.find(GuiFixture.id("shop")).orElseThrow().logic();

        assertThat(rules.all()).hasSize(6);
        assertThat(rules.rules(GuiRuleSet.OPEN)).hasSize(1);
        assertThat(rules.rules(GuiRuleSet.CLICK)).hasSize(3);
        assertThat(rules.clickRules("buy")).hasSize(2);
        assertThat(rules.clickRules("BUY")).as("layer ids compare case-insensitively").hasSize(2);
        assertThat(rules.clickRules("ghost")).hasSize(1);
        assertThat(rules.clickRules("nope")).isEmpty();
        assertThat(rules.unboundClickRules()).hasSize(1);
        assertThat(rules.ticks()).isTrue();
        assertThat(rules.tickPeriod()).isEqualTo(20);
        assertThat(rules.rules(GuiRuleSet.CLOSE)).isEmpty();
    }

    @Test
    void aGuiWithoutLogicHasTheEmptyRuleSet() {
        f.gui("""
                plain:
                  title: Plain
                  rows: 1
                  layers: []
                """);

        assertThat(f.guis.find(GuiFixture.id("plain")).orElseThrow().logic()).isSameAs(GuiRuleSet.EMPTY);
        assertThat(GuiRuleSet.EMPTY.ticks()).isFalse();
    }

    @Test
    void aBadRuleIsReportedAtItsStepPathAndItsSiblingsLoad() {
        List<Diagnostic> problems = check(HEAD + """
                    - on: gui.click
                      layer: buy
                      do:
                        - gui.close
                        - gui.teleport_cow: now
                    - on: gui.click
                      click: triple
                      do:
                        - gui.close
                """);

        assertThat(problems).anySatisfy(problem -> {
            assertThat(problem.code()).isEqualTo(DiagnosticCodes.LOGIC_STEP_REJECTED);
            assertThat(problem.target().field()).containsExactly("behavior", "0", "do", "1");
        });
        assertThat(problems).anySatisfy(problem -> {
            assertThat(problem.code()).isEqualTo(DiagnosticCodes.BEHAVIOR_RULE_INVALID);
            assertThat(problem.message()).contains("unknown click 'triple'");
            assertThat(problem.target().field()).containsExactly("behavior", "1", "click");
        });
    }

    @Test
    void aLayerFilterNamingNoLayerOrAHiddenOneIsAWarning() {
        List<Diagnostic> problems = check(HEAD + """
                    - on: gui.click
                      layer: [missing, ghost]
                      do:
                        - gui.close
                """);

        assertThat(problems).hasSize(2).allSatisfy(problem -> {
            assertThat(problem.severity()).isEqualTo(Severity.WARN);
            assertThat(problem.target().field()).containsExactly("behavior", "0", "layer");
        });
        assertThat(problems.get(0).message()).contains("'missing'").contains("does not have");
        assertThat(problems.get(1).message()).contains("'ghost'").contains("hidden");
    }

    @Test
    void aSlotOutsideTheContainerIsAWarning() {
        List<Diagnostic> problems = check(HEAD + """
                    - on: gui.click
                      slot: [17, 18]
                      do:
                        - gui.close
                """);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).message()).contains("slot 18").contains("2 row(s)");
        assertThat(problems.get(0).target().field()).containsExactly("behavior", "0", "slot");
    }

    @Test
    void aTriggerNoGuiFiresIsAWarning() {
        List<Diagnostic> problems = check(HEAD + """
                    - on: click
                      do:
                        - gui.close
                """);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).message()).contains("never fired by a GUI");
    }

    @Test
    void aTickRuleNeedsAnEveryOfAtLeastOne() {
        assertThat(check(HEAD + """
                    - on: gui.tick
                      do:
                        - gui.refresh
                """)).anySatisfy(problem -> assertThat(problem.message()).contains("needs 'every:'"));
        assertThat(check(HEAD + """
                    - on: gui.tick
                      every: 0
                      do:
                        - gui.refresh
                """)).anySatisfy(problem -> assertThat(problem.message()).contains("at least 1"));
    }

    @Test
    void anUnknownVariableRootIsCheckedAgainstTheGuiTriggersSchema() {
        List<Diagnostic> problems = check(HEAD + """
                    - on: gui.click
                      layer: buy
                      do:
                        - message: "${plyer.name} ${click.slot} ${gui.args.x}"
                """);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).code()).isEqualTo(DiagnosticCodes.FLOW_UNKNOWN_CONTEXT_ROOT);
        assertThat(problems.get(0).message()).contains("'plyer'");
    }

    @Test
    void namedFlowsAreCompiledWithTheGuiRulesAndCallsAreLinked() {
        List<Diagnostic> problems = check("""
                shop:
                  title: Shop
                  rows: 1
                  layers: []
                  behavior:
                    - on: gui.open
                      do:
                        - call: greet
                    - on: gui.close
                      do:
                        - call: missing
                  flows:
                    greet:
                      - gui.refresh
                """);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).code()).isEqualTo(DiagnosticCodes.FLOW_CALL_INVALID);
    }
}
