package dev.materione.plugin.commands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.behavior.flow.AllCond;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.event.GuiContexts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The compile of an {@code open:} block (logic.md §6.1): arguments typed at load, the requirement as a
 * condition that fails closed when it does not load, and the deny steps as a rule. Compiled through the real
 * GUI compiler and the real registries, the same way the live view compiles them.
 */
class OpenPlanTest {

    private GuiFixture f;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
    }

    private OpenPlan plan(String yaml) {
        var definition = f.definition(yaml);
        return OpenPlan.compile(definition.id(), definition.rules(), f.logic.lookup());
    }

    private static String shop(String open) {
        return "shop:\n  title: Shop\n  container: chest_3\n  layers: []\n  open:\n" + open;
    }

    @Test
    void aGuiWithoutAnOpenBlockHasNoPlan() {
        assertThat(plan("shop:\n  title: Shop\n  container: chest_1\n  layers: []\n")).isNull();
    }

    @Test
    void commandsPermissionAndTypedArgumentsCompile() {
        OpenPlan plan = plan(shop("""
                    commands: [shop, store]
                    permission: shop.use
                    args:
                      - { name: category, type: choice, default: all, suggest: [all, tools] }
                      - { name: amount, type: int, optional: true }
                """));

        assertThat(plan.commands()).containsExactly("shop", "store");
        assertThat(plan.permission()).isEqualTo("shop.use");
        assertThat(plan.args()).containsExactly(
                new OpenArgSpec("category", ArgType.CHOICE, false, "all", java.util.List.of("all", "tools")),
                new OpenArgSpec("amount", ArgType.INT, true, null, java.util.List.of()));
        assertThat(plan.problems()).isEmpty();
    }

    @Test
    void boundsAndPlayerOptionsAndVisibilityCompile() {
        OpenPlan plan = plan(shop("""
                    commands: [shop]
                    permission: shop.use
                    visible: always
                    args:
                      - { name: amount, type: int, min: 1.5, max: 9.9, default: 3 }
                      - { name: ratio, type: number, min: 0 }
                      - { name: who, type: player, offline: true, selector: true }
                """));

        assertThat(plan.visibleAlways()).isTrue();
        // an int rounds its bounds inward
        assertThat(plan.args().get(0).min()).isEqualTo(2.0);
        assertThat(plan.args().get(0).max()).isEqualTo(9.0);
        assertThat(plan.args().get(0).defaultValue()).isEqualTo("3");
        assertThat(plan.args().get(1).min()).isEqualTo(0.0);
        assertThat(plan.args().get(1).max()).isNull();
        assertThat(plan.args().get(2).offline()).isTrue();
        assertThat(plan.args().get(2).selector()).isTrue();
        assertThat(plan.problems()).isEmpty();
    }

    @Test
    void aDefaultOutsideItsBoundsIsDroppedAndAnEmptyRangeIsIgnored() {
        OpenPlan plan = plan(shop("""
                    commands: [shop]
                    args:
                      - { name: amount, type: int, min: 1, max: 5, default: 50 }
                      - { name: other, type: int, min: 9, max: 2 }
                """));

        assertThat(plan.args().get(0).defaultValue()).isNull();
        assertThat(plan.args().get(1).min()).isNull();
        assertThat(plan.args().get(1).max()).isNull();
        assertThat(plan.problems()).extracting(Diagnostic::code)
                .containsExactly(DiagnosticCodes.OPEN_ARGUMENT_INVALID, DiagnosticCodes.OPEN_ARGUMENT_INVALID);
    }

    @Test
    void aCommandIsVisibleOnlyToThoseWhoMayRunItUnlessItIsAlwaysVisible() {
        OpenPlan permitted = plan(shop("""
                    commands: [shop]
                    permission: shop.use
                """));
        OpenPlan always = plan(shop("""
                    commands: [shop]
                    permission: shop.use
                    visible: always
                """));
        OpenPlan open = plan(shop("""
                    commands: [shop]
                """));
        java.util.function.Function<Boolean, org.bukkit.command.CommandSender> holder = permission -> (org.bukkit.command.CommandSender) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {org.bukkit.command.CommandSender.class},
                (proxy, method, args) -> "hasPermission".equals(method.getName()) ? permission : null);
        org.bukkit.command.CommandSender with = holder.apply(true);
        org.bukkit.command.CommandSender without = holder.apply(false);

        assertThat(permitted.visibleAlways()).isFalse();
        assertThat(permitted.visibleTo(with)).isTrue();
        assertThat(permitted.visibleTo(without)).isFalse();
        assertThat(always.visibleTo(without)).isTrue();
        assertThat(open.visibleTo(without)).isTrue(); // no permission: nothing to hide it behind
    }

    @Test
    void aDefaultThatDoesNotReadAsItsTypeIsDroppedWithAWarning() {
        OpenPlan plan = plan(shop("""
                    commands: [shop]
                    args:
                      - { name: amount, type: int, default: lots }
                """));

        assertThat(plan.args().get(0).defaultValue()).isNull();
        assertThat(plan.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(DiagnosticCodes.OPEN_ARGUMENT_INVALID);
            assertThat(problem.severity()).isEqualTo(Severity.WARN);
        });
    }

    @Test
    void aChoiceWithoutItsValuesReadsAsAString() {
        OpenPlan plan = plan(shop("""
                    commands: [shop]
                    args:
                      - { name: category, type: choice }
                """));

        assertThat(plan.args().get(0).type()).isEqualTo(ArgType.STRING);
        assertThat(plan.problems()).extracting(Diagnostic::code)
                .containsExactly(DiagnosticCodes.OPEN_ARGUMENT_INVALID);
    }

    @Test
    void aRequirementThatDoesNotLoadFailsClosedAndSaysSo() {
        OpenPlan plan = plan(shop("""
                    commands: [shop]
                    requirement: { no_such_condition: 1 }
                """));

        assertThat(plan.hasRequirement()).isTrue();
        assertThat(plan.requirement()).isNull();
        assertThat(plan.requirementMet(GuiContexts.open(f.viewer, GuiFixture.id("shop"), Map.of()))).isFalse();
        assertThat(plan.problems()).extracting(Diagnostic::code)
                .contains(DiagnosticCodes.OPEN_REQUIREMENT_INVALID);
        assertThat(plan.problems()).filteredOn(problem -> problem.code() == DiagnosticCodes.OPEN_REQUIREMENT_INVALID)
                .singleElement().satisfies(problem -> assertThat(problem.severity()).isEqualTo(Severity.ERROR));
    }

    @Test
    void aRequirementReadsTheTypedArgumentsAndHoldsOrNot() {
        OpenPlan plan = plan(shop("""
                    commands: [shop]
                    args:
                      - { name: category, type: string, default: all }
                    requirement: "gui.args.category == 'tools'"
                """));

        assertThat(plan.problems()).isEmpty();
        assertThat(plan.requirementMet(GuiContexts.open(f.viewer, GuiFixture.id("shop"),
                Map.of("category", "tools")))).isTrue();
        assertThat(plan.requirementMet(GuiContexts.open(f.viewer, GuiFixture.id("shop"),
                Map.of("category", "food")))).isFalse();
    }

    @Test
    void denyStepsCompileToARuleWithNoCondition() {
        OpenPlan plan = plan(shop("""
                    commands: [shop]
                    requirement: "player.level >= 10"
                    deny:
                      - mark: "refused"
                """));

        assertThat(plan.deny()).isNotNull();
        assertThat(plan.deny().id()).isEqualTo("open.deny");
        assertThat(plan.deny().steps()).hasSize(1);
        // the requirement is checked by the gate; the deny rule runs whenever it is asked to
        assertThat(plan.deny().condition()).isEqualTo(new AllCond(List.of()));
        assertThat(plan.problems()).isEmpty();
    }

    @Test
    void denyStepsThatDoNotLoadLeaveNoRuleAndAReason() {
        OpenPlan plan = plan(shop("""
                    commands: [shop]
                    requirement: "player.level >= 10"
                    deny:
                      - no_such_action: now
                """));

        assertThat(plan.deny()).isNull();
        assertThat(plan.problems()).isNotEmpty();
        assertThat(plan.problems().get(0).severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void theBypassNodeNamesThePackAndTheGui() {
        assertThat(OpenPlan.bypassNode(GuiFixture.id("shop"))).isEqualTo("materione.gui.bypass.demo.shop");
    }
}
