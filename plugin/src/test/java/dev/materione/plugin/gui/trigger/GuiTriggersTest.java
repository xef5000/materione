package dev.materione.plugin.gui.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.registry.ParameterSchema;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The six GUI triggers describe themselves: filters, context schema, cancellability (logic.md §6.2). */
class GuiTriggersTest {

    private static TriggerType trigger(String id) {
        return GuiTriggers.all().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<String> contextPaths(String id) {
        return trigger(id).describe().context().stream().map(ContextVariable::path).toList();
    }

    @Test
    void theSixTriggersAreFiredByGuis() {
        assertThat(GuiTriggers.all()).extracting(TriggerType::id).containsExactly(
                "gui.opening", "gui.open", "gui.close", "gui.click", "gui.click_outside", "gui.tick");
        assertThat(GuiTriggers.all()).allSatisfy(trigger -> {
            assertThat(trigger.describe().firedBy()).containsExactly("gui");
            assertThat(trigger.describe().fired()).isTrue();
        });
    }

    @Test
    void onlyOpeningCanBeCancelled() {
        assertThat(GuiTriggers.all().stream().filter(t -> t.describe().cancellable()).map(TriggerType::id))
                .containsExactly("gui.opening");
    }

    @Test
    void everyTriggerGuaranteesThePlayerTheServerAndTheGui() {
        for (TriggerType trigger : GuiTriggers.all()) {
            assertThat(contextPaths(trigger.id())).contains(
                    "player.name", "player.uuid", "server.online", "gui.id", "gui.args.*", "gui.session.*", "vars.*");
        }
    }

    @Test
    void clickAddsTheClickAndLayerVariables() {
        assertThat(contextPaths("gui.click")).contains("click.type", "click.slot", "click.key", "layer.id");
        assertThat(contextPaths("gui.click_outside")).contains("click.type").doesNotContain("layer.id");
        assertThat(contextPaths("gui.open")).doesNotContain("click.type");
        assertThat(contextPaths("gui.close")).contains("close.reason");
    }

    @Test
    void clickFiltersAreLayerSlotAndTheClickKinds() {
        List<ParameterSchema> filters = trigger("gui.click").describe().filters();

        assertThat(filters).extracting(ParameterSchema::id).containsExactly("layer", "slot", "click");
        assertThat(filters.get(2).allowedValues()).containsExactly("left", "right", "shift_left", "shift_right",
                "middle", "double", "drop", "ctrl_drop", "number_key", "swap_offhand");
        assertThat(filters.get(1).editor().min()).isEqualTo(0.0);
    }

    @Test
    void closeFiltersOnItsReasonsAndTickRequiresEvery() {
        assertThat(trigger("gui.close").describe().filters().get(0).allowedValues())
                .containsExactly("player", "action", "replaced", "quit");
        ParameterSchema every = trigger("gui.tick").describe().filters().get(0);
        assertThat(every.id()).isEqualTo("every");
        assertThat(every.required()).isTrue();
        assertThat(every.editor().min()).isEqualTo(1.0);
        assertThat(trigger("gui.click_outside").describe().filters().get(0).allowedValues())
                .containsExactly("left", "right");
    }
}
