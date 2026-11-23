package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.plugin.gui.rules.GuiRuleSet;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.input.ClickKind;
import dev.materione.plugin.gui.input.GuiClick;
import dev.materione.plugin.view.open.OpenRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Declared session variables as an author meets them (views.md section 3.4): a rule's {@code set_var}, {@code var}
 * condition and {@code vars.session.<pack>.<key>} template go through the real GUI runtime, a name no pack declares is
 * {@code MTR-0063} at load, and the variable dies with its window.
 */
class SessionVariablesGuiTest {

    private static final String MENU = """
            menu:
              title: Menu
              container: chest_3
              layers:
                - { id: go, type: button, x: 8, y: 18, width: 16, height: 16 }
              behavior:
                - on: gui.click
                  layer: go
                  do:
                    - set_var: { var: session.tab, value: "tools" }
                    - set_var: { var: session.clicks, op: add, value: 1 }
                    - if:
                        - var: { var: session.tab, value: tools }
                      then:
                        - mark: "tab=${vars.session.demo.tab} clicks=${vars.session.demo.clicks}"
            """;

    private GuiFixture f;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        f.declareSession("tab", "all");
        f.declareSession("clicks", 0d);
    }

    /** The load problems of the rules of a GUI written as YAML, compiled against the live declarations. */
    private java.util.List<Diagnostic> problems(String yaml) {
        var definition = f.definition(yaml);
        return GuiRuleSet.compile(GuiFixture.id("menu"), definition.rules(), f.logic.lookup()).problems();
    }

    private ViewerRecord open() {
        return f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("menu"), Map.of(), OpenRequest.Cause.API)).record();
    }

    @Test
    void aRuleWritesReadsAndComparesADeclaredSessionVariable() {
        f.gui(MENU);
        ViewerRecord menu = open();
        assertThat(f.session(menu, "tab")).isEqualTo("all");

        f.router.route(new GuiClick(f.viewer, menu, 0, ClickKind.LEFT, 0));
        f.router.route(new GuiClick(f.viewer, menu, 0, ClickKind.LEFT, 0));

        assertThat(f.session(menu, "tab")).isEqualTo("tools");
        assertThat(f.session(menu, "clicks")).isEqualTo(2.0);
        assertThat(f.ran).containsExactly("mark:tab=tools clicks=1", "mark:tab=tools clicks=2");
        assertThat(f.diagnostics).isEmpty();
    }

    @Test
    void aNeverWrittenVariableReadsItsDefaultInATemplate() {
        f.gui("""
                menu:
                  title: Menu
                  container: chest_3
                  layers: []
                  behavior:
                    - on: gui.open
                      do:
                        - mark: "tab=${vars.session.demo.tab}"
                """);

        open();

        assertThat(f.ran).containsExactly("mark:tab=all");
    }

    @Test
    void anUndeclaredWriteIsMtr0063AtLoadAndTheStepIsSkipped() {
        var problems = problems("""
                menu:
                  title: Menu
                  container: chest_3
                  layers: []
                  behavior:
                    - on: gui.open
                      do:
                        - set_var: { var: session.typo, value: "x" }
                        - mark: "after"
                """);

        assertThat(problems).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.VARIABLE_UNDECLARED);
            assertThat(diagnostic.message()).contains("demo:typo");
        });
    }

    @Test
    void anUndeclaredReadIsMtr0063AtLoad() {
        var problems = problems("""
                menu:
                  title: Menu
                  container: chest_3
                  layers: []
                  behavior:
                    - on: gui.open
                      do:
                        - if:
                            - var: { var: session.typo, value: "x" }
                          then:
                            - mark: "never"
                """);

        assertThat(problems).anySatisfy(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.VARIABLE_UNDECLARED));
    }

    @Test
    void theValuesDieWithTheWindowAndANewWindowStartsFromTheDefault() {
        f.gui(MENU);
        ViewerRecord first = open();
        f.router.route(new GuiClick(f.viewer, first, 0, ClickKind.LEFT, 0));
        assertThat(f.session(first, "tab")).isEqualTo("tools");

        f.sessions.end(first, CloseReason.PLAYER);
        ViewerRecord second = open();

        assertThat(second.instance()).isNotEqualTo(first.instance());
        assertThat(f.session(second, "tab")).isEqualTo("all");
        assertThat(f.variables.service().store(dev.materione.api.logic.variable.VariableScope.SESSION)
                .read(f.variables.service().declaration(
                        dev.materione.api.NamespacedId.of(GuiFixture.PACK, "tab")).orElseThrow(), first.instance()))
                .isEmpty();
    }
}
