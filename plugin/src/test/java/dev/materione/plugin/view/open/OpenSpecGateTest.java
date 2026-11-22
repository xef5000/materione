package dev.materione.plugin.view.open;

import dev.materione.plugin.commands.PlayerNames;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.behavior.rule.Rule;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.open.GuiOpenGates;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.open.OpenResult;
import dev.materione.plugin.view.open.OpenGate.Admission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The open gate (logic.md §6.1, stages 2 to 4) and the opener that runs it: the permission, the typed
 * arguments and their usage message, the requirement with its bypass, and the deny flow the failed requirement
 * runs through the flow engine. The fixture is the real GUI compiler, rule engine and opener, with a recording
 * window host.
 */
class OpenSpecGateTest {

    private static final String SHOP = """
            shop:
              title: Shop
              container: chest_3
              layers: []
              open:
                commands: [shop, store]
                permission: shop.use
                args:
                  - { name: category, type: choice, default: all, suggest: [all, tools, food] }
                requirement: "gui.args.category == 'tools'"
                deny:
                  - mark: "refused"
            """;

    private GuiFixture f;
    private List<Rule> denied;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        denied = new ArrayList<>();
    }

    private OpenSpecGate gate(OpenSpecGate.Permissions permissions) {
        return GuiOpenGates.of(permissions, PlayerNames.NONE, (rule, context, owner) -> {
            denied.add(rule);
            f.engine.interpreter().start(rule, context, owner);
        });
    }

    private static OpenSpecGate.Permissions granted(String... nodes) {
        List<String> held = List.of(nodes);
        return (viewer, node) -> held.contains(node);
    }

    private Admission admit(OpenSpecGate gate, RuntimeGuis.Live gui, Map<String, String> args) {
        return gate.admit(f.viewer, new OpenGate.Subject(GuiOpenGates.KIND, gui.id(), gui.open()), args);
    }

    @Test
    void aGuiWithoutAnOpenBlockIsAdmittedWithItsArgumentsAsGiven() {
        RuntimeGuis.Live gui = f.gui("plain:\n  title: Plain\n  container: chest_1\n  layers: []\n");

        Admission admission = admit(gate(granted()), gui, Map.of("anything", "goes"));

        assertThat(admission.allowed()).isTrue();
        assertThat(admission.args()).containsExactly(Map.entry("anything", "goes"));
    }

    @Test
    void theOpenPermissionIsCheckedBeforeAnythingElse() {
        RuntimeGuis.Live gui = f.gui(SHOP);

        Admission admission = admit(gate(granted()), gui, Map.of("category", "bogus"));

        assertThat(admission.allowed()).isFalse();
        assertThat(admission.usage()).isFalse();
        assertThat(admission.reason()).isEqualTo("no permission (shop.use)");
    }

    @Test
    void aBadArgumentAnswersWithTheUsageLine() {
        RuntimeGuis.Live gui = f.gui(SHOP);

        Admission admission = admit(gate(granted("shop.use")), gui, Map.of("category", "bogus"));

        assertThat(admission.allowed()).isFalse();
        assertThat(admission.usage()).isTrue();
        assertThat(admission.reason()).isEqualTo(
                "category: 'bogus' is not one of all, tools, food. "
                        + "usage: /materione gui open demo:shop [category:all|tools|food=all]");
    }

    @Test
    void aPassingOpenCarriesTheTypedArgumentsAndDefaults() {
        RuntimeGuis.Live gui = f.gui(SHOP);

        Admission admission = admit(gate(granted("shop.use", "materione.gui.bypass.demo.shop")), gui, Map.of());

        assertThat(admission.allowed()).isTrue();
        assertThat(admission.args()).containsEntry("category", "all");
    }

    private static final String COUNTER = """
            counter:
              title: Counter
              container: chest_3
              layers: []
              open:
                commands: [counter]
                args:
                  - { name: n, type: int, min: 1, max: 100, default: "1" }
                  - { name: ratio, type: number, optional: true }
                  - { name: vip, type: boolean, optional: true }
                  - { name: label, type: string, optional: true }
                requirement: "gui.args.n > 5"
            """;

    @Test
    void guiArgsAreTypedExactlyAsACommandsArgsAre() {
        RuntimeGuis.Live gui = f.gui(COUNTER);
        f.opener.gate(gate(granted()));

        var opened = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("counter"),
                Map.of("n", "10", "ratio", "2", "vip", "TRUE", "label", "x"), OpenRequest.Cause.API));

        assertThat(opened.ok()).isTrue();
        // the text is kept (a snapshot and gui.back replay it); what a rule reads is typed
        assertThat(opened.session().args()).containsEntry("n", "10").containsEntry("ratio", "2.0");
        assertThat(opened.session().typedArgs())
                .containsEntry("n", 10).containsEntry("ratio", 2.0).containsEntry("vip", true)
                .containsEntry("label", "x");
        var context = dev.materione.plugin.gui.event.GuiContexts.build(f.viewer, opened.session(), Map.of());
        assertThat(context.resolve(List.of("gui", "args", "n"))).isEqualTo(10);
        assertThat(context.resolve(List.of("gui", "args", "vip"))).isEqualTo(true);
        assertThat(gui.open()).isNotNull();
    }

    @Test
    void aNumericRequirementOnATypedGuiArgComparesNumbersAndTheBoundsRefuseAtTheGate() {
        RuntimeGuis.Live gui = f.gui(COUNTER);

        // 10 > 5 holds as numbers; "10" > "5" as text would not
        assertThat(admit(gate(granted()), gui, Map.of("n", "10")).allowed()).isTrue();
        assertThat(admit(gate(granted()), gui, Map.of("n", "3")).allowed()).isFalse();
        Admission outOfRange = admit(gate(granted()), gui, Map.of("n", "101"));
        assertThat(outOfRange.usage()).isTrue();
        assertThat(outOfRange.reason()).contains("n: 101 is above the maximum 100").contains("[n:int(1..100)=1]");
    }

    @Test
    void aGuiWithNoOpenBlockKeepsItsArgsAsTextForRules() {
        RuntimeGuis.Live gui = f.gui("plain:\n  title: Plain\n  container: chest_1\n  layers: []\n");
        f.opener.gate(gate(granted()));

        var opened = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("plain"), Map.of("n", "10"),
                OpenRequest.Cause.API));

        assertThat(opened.session().typedArgs()).containsEntry("n", "10");
        assertThat(gui.open()).isNull();
    }

    @Test
    void aFailedRequirementRunsTheDenyStepsThroughTheEngineAndRefuses() {
        RuntimeGuis.Live gui = f.gui(SHOP);

        Admission admission = admit(gate(granted("shop.use")), gui, Map.of("category", "food"));

        assertThat(admission.allowed()).isFalse();
        assertThat(admission.usage()).isFalse();
        assertThat(denied).hasSize(1);
        assertThat(f.ran).containsExactly("mark:refused");
    }

    @Test
    void theRequirementHoldsSoTheOpenGoesOnWithNoDeny() {
        RuntimeGuis.Live gui = f.gui(SHOP);

        Admission admission = admit(gate(granted("shop.use")), gui, Map.of("category", "tools"));

        assertThat(admission.allowed()).isTrue();
        assertThat(admission.args()).containsEntry("category", "tools");
        assertThat(f.ran).isEmpty();
    }

    @Test
    void theBypassNodeSkipsTheRequirementButNotTheArgumentTypes() {
        RuntimeGuis.Live gui = f.gui(SHOP);
        OpenSpecGate bypass = gate(granted("shop.use", "materione.gui.bypass.demo.shop"));

        assertThat(admit(bypass, gui, Map.of("category", "food")).allowed()).isTrue();
        assertThat(f.ran).isEmpty();
        assertThat(admit(bypass, gui, Map.of("category", "bogus")).usage()).isTrue();
    }

    @Test
    void aRequirementThatDidNotLoadRefusesEveryoneWithoutTheBypass() {
        RuntimeGuis.Live gui = f.gui("""
                broken:
                  title: Broken
                  container: chest_1
                  layers: []
                  open:
                    commands: [broken]
                    requirement: { no_such_condition: 1 }
                """);

        assertThat(admit(gate(granted()), gui, Map.of()).allowed()).isFalse();
        assertThat(admit(gate(granted("materione.gui.bypass.demo.broken")), gui, Map.of()).allowed()).isTrue();
    }

    @Test
    void theOpenerRefusesABadArgumentWithAUsageOutcomeAndOpensNothing() {
        f.opener.gate(gate(granted("shop.use")));
        RuntimeGuis.Live gui = f.gui(SHOP);

        OpenResult result = f.opener.open(
                OpenRequest.of(f.viewer, gui.id(), Map.of("category", "bogus"), OpenRequest.Cause.API));

        assertThat(result.outcome()).isEqualTo(OpenResult.Outcome.USAGE);
        assertThat(f.windows.log).isEmpty();
        assertThat(f.sessions.current(f.viewer.id())).isEmpty();
    }

    @Test
    void theOpenerOpensWhenTheGateAdmits() {
        f.opener.gate(gate(granted("shop.use", "materione.gui.bypass.demo.shop")));
        RuntimeGuis.Live gui = f.gui(SHOP);

        OpenResult result = f.opener.open(
                OpenRequest.of(f.viewer, gui.id(), Map.of("category", "tools"), OpenRequest.Cause.API));

        assertThat(result.outcome()).isEqualTo(OpenResult.Outcome.OPENED);
        assertThat(f.windows.log).containsExactly("open shop");
    }

    @Test
    void theOpenerRefusesAFailedRequirementAsDeniedNotAsUsage() {
        f.opener.gate(gate(granted("shop.use")));
        RuntimeGuis.Live gui = f.gui(SHOP);

        OpenResult result = f.opener.open(
                OpenRequest.of(f.viewer, gui.id(), Map.of("category", "food"), OpenRequest.Cause.API));

        assertThat(result.outcome()).isEqualTo(OpenResult.Outcome.DENIED);
        assertThat(f.windows.log).isEmpty();
        assertThat(f.ran).containsExactly("mark:refused");
    }
}
