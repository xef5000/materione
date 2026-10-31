package dev.materione.plugin.gui.open;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.behavior.rule.Rule;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.open.OpenGate.Admission;
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
              rows: 3
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
        return new OpenSpecGate(permissions, PlayerNames.NONE, (rule, context, owner) -> {
            denied.add(rule);
            f.engine.interpreter().start(rule, context, owner);
        });
    }

    private static OpenSpecGate.Permissions granted(String... nodes) {
        List<String> held = List.of(nodes);
        return (viewer, node) -> held.contains(node);
    }

    private Admission admit(OpenSpecGate gate, RuntimeGuis.Live gui, Map<String, String> args) {
        return gate.admit(f.viewer, gui, args);
    }

    @Test
    void aGuiWithoutAnOpenBlockIsAdmittedWithItsArgumentsAsGiven() {
        RuntimeGuis.Live gui = f.gui("plain:\n  title: Plain\n  rows: 1\n  layers: []\n");

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
                  rows: 1
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
