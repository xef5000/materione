package dev.materione.plugin.view.open;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.plugin.commands.PlayerNames;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.view.open.OpenGate.Admission;
import dev.materione.plugin.view.open.OpenGate.Subject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Row G of dialogs.md section 13.3: the open gate is one piece of code for every opened view kind, so the same table
 * of permission, requirement, deny, bypass and argument cases runs against a {@code gui} subject and a
 * {@code dialog} subject and the decisions match. The only things allowed to differ are the two strings that name
 * the kind: the usage line's command and the bypass node. {@code OpenSpecGateTest} pins the GUI side in detail;
 * this test pins that the second kind cannot drift from it.
 */
class OpenGateParityTest {

    private static final String SHOP = """
            shop:
              title: Shop
              container: chest_3
              layers: []
              open:
                commands: [shop]
                permission: shop.use
                args:
                  - { name: category, type: choice, default: all, suggest: [all, tools, food] }
                  - { name: count, type: int, min: 1, max: 9, default: "1" }
                requirement: "gui.args.category == 'tools'"
                deny:
                  - mark: "refused"
            """;

    private GuiFixture f;
    private int denyRuns;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        denyRuns = 0;
    }

    /** One gate per kind. Both build the requirement's context the same way: the gate is what is under test. */
    private OpenGate gate(List<String> held) {
        return new OpenSpecGate((viewer, node) -> held.contains(node), PlayerNames.NONE,
                (rule, context, owner) -> denyRuns++, null, GuiContexts::open);
    }

    private Subject subject(String kind) {
        RuntimeGuis.Live gui = f.gui(SHOP);
        return new Subject(kind, gui.id(), gui.open());
    }

    /** What a case decided, with the kind-named strings normalised away. */
    private record Decision(boolean allowed, boolean usage, Map<String, String> args, String reason, int denyRuns) {}

    private Decision decide(String kind, List<String> held, Map<String, String> args) {
        denyRuns = 0;
        Subject subject = subject(kind);
        Admission admission = gate(held).admit(f.viewer, subject, args);
        String reason = admission.reason().replace("/materione " + kind + " open", "/materione <kind> open");
        return new Decision(admission.allowed(), admission.usage(), admission.args(), reason, denyRuns);
    }

    private void assertParity(List<String> held, Map<String, String> args) {
        Decision gui = decide("gui", held, args);
        Decision dialog = decide("dialog", held, args);
        assertThat(dialog).as("a dialog is gated exactly as a GUI is").isEqualTo(gui);
    }

    @Test
    void thePermissionCaseMatches() {
        assertParity(List.of(), Map.of("category", "tools"));
        assertThat(decide("dialog", List.of(), Map.of()).reason()).isEqualTo("no permission (shop.use)");
    }

    @Test
    void theArgumentCasesMatch() {
        assertParity(List.of("shop.use"), Map.of("category", "bogus"));
        assertParity(List.of("shop.use"), Map.of("count", "10"));
        assertParity(List.of("shop.use"), Map.of("count", "x"));
        Decision usage = decide("dialog", List.of("shop.use"), Map.of("category", "bogus"));
        assertThat(usage.usage()).isTrue();
        assertThat(usage.reason()).contains("usage: /materione <kind> open demo:shop");
    }

    @Test
    void theRequirementAndDenyCasesMatch() {
        assertParity(List.of("shop.use"), Map.of("category", "tools"));
        assertParity(List.of("shop.use"), Map.of("category", "food"));
        Decision refused = decide("dialog", List.of("shop.use"), Map.of("category", "food"));
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.denyRuns()).as("the deny steps ran once").isEqualTo(1);
    }

    @Test
    void theDefaultsAreAppliedTheSameWay() {
        assertParity(List.of("shop.use", "materione.gui.bypass.demo.shop", "materione.dialog.bypass.demo.shop"),
                Map.of());
        Decision defaults = decide("dialog", List.of("shop.use", "materione.dialog.bypass.demo.shop"), Map.of());
        assertThat(defaults.allowed()).isTrue();
        assertThat(defaults.args()).containsEntry("category", "all").containsEntry("count", "1");
    }

    @Test
    void theBypassNodeIsTheKindsOwn() {
        NamespacedId id = NamespacedId.of("demo", "shop");
        assertThat(new Subject("gui", id, null).bypassNode()).isEqualTo("materione.gui.bypass.demo.shop");
        assertThat(new Subject("dialog", id, null).bypassNode()).isEqualTo("materione.dialog.bypass.demo.shop");

        // the GUI's node does not skip a dialog's requirement, and the reverse
        Decision wrongNode = decide("dialog", List.of("shop.use", "materione.gui.bypass.demo.shop"),
                Map.of("category", "food"));
        assertThat(wrongNode.allowed()).isFalse();
        Decision rightNode = decide("dialog", List.of("shop.use", "materione.dialog.bypass.demo.shop"),
                Map.of("category", "food"));
        assertThat(rightNode.allowed()).isTrue();
        assertThat(rightNode.denyRuns()).isZero();
    }

    @Test
    void aSubjectWithNoOpenBlockIsAdmittedAsGivenForEveryKind() {
        List<Decision> all = new ArrayList<>();
        for (String kind : List.of("gui", "dialog")) {
            Admission admission = gate(List.of()).admit(f.viewer,
                    new Subject(kind, NamespacedId.of("demo", "plain"), null), Map.of("anything", "goes"));
            all.add(new Decision(admission.allowed(), admission.usage(), admission.args(), admission.reason(), 0));
        }
        assertThat(all.get(1)).isEqualTo(all.get(0));
        assertThat(all.get(0).allowed()).isTrue();
        assertThat(all.get(0).args()).containsEntry("anything", "goes");
    }
}
