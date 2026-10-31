package dev.materione.plugin.logic.action.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.ParamReader;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.gui.input.SlotOp;
import dev.materione.plugin.gui.input.StackView;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.logic.condition.SlotHasCondition;
import dev.materione.behavior.logic.EngineExpressions;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The input-slot condition and action (logic.md §25): each parses strictly, and each runs against the real GUI
 * runtime over a fake window, so the checks an author relies on (an open GUI, an input slot, the item in it) are
 * the ones a click sees.
 */
class InputSlotActionsTest {

    /** A shop whose first nine slots take gems that cost less than 100 (a condition on slot.item only). */
    private static final String SHOP = """
            shop:
              title: Shop
              rows: 3
              layers:
                - id: wells
                  type: slots
                  region: container
                  input: true
                  accept: "${slot.item == 'corpus:gem_red'}"
            """;

    private GuiFixture f;
    private GuiSession shop;

    @BeforeEach
    void open() {
        f = new GuiFixture();
        f.gui(SHOP);
        shop = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("shop"), Map.of(), OpenRequest.Cause.API))
                .session();
    }

    private static ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE);
    }

    @Test
    void slotHasParsesItsSlotItemAndAmountAndRejectsAnOutOfRangeSlot() {
        var condition = new SlotHasCondition(f.bridge);
        assertThat(condition.parse(reader(Map.of("slot", 4, "item", "corpus:gem_red", "amount", 2)))).isNotNull();
        assertThat(condition.parse(reader(Map.of("slot", 4, "item", "diamond")))).isNotNull();
        assertThatThrownBy(() -> condition.parse(reader(Map.of("slot", 60))))
                .as("slot 60 is past any chest").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> condition.parse(reader(Map.of())))
                .as("a slot is required").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void slotHasReadsTheInputSlotOfTheRunsOwnGuiAndNothingElse() {
        var condition = new SlotHasCondition(f.bridge);
        f.inputPort.items.put(4, new StackView("corpus:gem_red", 2, 64));
        var ctx = GuiContexts.build(f.viewer, shop, Map.of());

        assertThat(condition.test(condition.parse(reader(Map.of("slot", 4))), ctx)).isTrue();
        assertThat(condition.test(condition.parse(reader(Map.of("slot", 4, "item", "corpus:gem_red", "amount", 2))), ctx))
                .isTrue();
        assertThat(condition.test(condition.parse(reader(Map.of("slot", 4, "amount", 3))), ctx))
                .as("two held, three asked").isFalse();
        assertThat(condition.test(condition.parse(reader(Map.of("slot", 4, "item", "minecraft:diamond"))), ctx))
                .isFalse();
        assertThat(condition.test(condition.parse(reader(Map.of("slot", 5))), ctx)).as("an empty slot").isFalse();
        assertThat(condition.test(condition.parse(reader(Map.of("slot", 4))),
                new MapLogicContext(Map.of()).with(Facets.PLAYER, f.player)))
                .as("a run with no GUI holds nothing").isFalse();
    }

    @Test
    void slotItemParsesEachOperationAndRejectsTheIncompleteOnes() {
        var action = new GuiSlotItemAction(f.bridge);
        assertThat(action.parse(reader(Map.of("slot", 4, "op", "clear"))).op().kind()).isEqualTo(SlotOp.Kind.CLEAR);
        assertThat(action.parse(reader(Map.of("slot", 4, "op", "set_amount", "amount", 3))).op().amount()).isEqualTo(3);
        assertThat(action.parse(reader(Map.of("slot", 4, "op", "enchant", "enchantment", "sharpness", "level", 5)))
                .op().level()).isEqualTo(5);
        assertThatThrownBy(() -> action.parse(reader(Map.of("slot", 4, "op", "shatter"))))
                .as("an unknown operation").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of("slot", 4, "op", "set_amount"))))
                .as("set_amount needs an amount").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of("slot", 4, "op", "enchant"))))
                .as("enchant needs an enchantment").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of("slot", 4, "op", "enchant", "enchantment", "sharpness", "level", 0))))
                .as("a level is at least 1").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void slotItemAppliesItsOperationToTheRunsOwnWindow() {
        var action = new GuiSlotItemAction(f.bridge);
        var ctx = GuiContexts.build(f.viewer, shop, Map.of());

        ActionOutcome outcome = action.execute(action.parse(reader(Map.of("slot", 4, "op", "take"))), ctx);

        assertThat(outcome).isEqualTo(ActionOutcome.OK);
        assertThat(f.inputPort.applied).containsExactly("4:take");
    }

    @Test
    void slotItemReportsTheReasonAPortRefuses() {
        var action = new GuiSlotItemAction(f.bridge);
        f.inputPort.refuse = "slot 4 is empty";

        ActionOutcome outcome = action.execute(action.parse(reader(Map.of("slot", 4, "op", "clear"))),
                GuiContexts.build(f.viewer, shop, Map.of()));

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(((ActionOutcome.Failed) outcome).reason()).isEqualTo("slot 4 is empty");
    }

    @Test
    void slotItemFailsWithNoOpenGuiWhenTheRunHasNoSession() {
        var action = new GuiSlotItemAction(f.bridge);
        ActionOutcome outcome = action.execute(action.parse(reader(Map.of("slot", 4, "op", "clear"))),
                new MapLogicContext(Map.of()).with(Facets.PLAYER, f.player));

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(((ActionOutcome.Failed) outcome).reason()).isEqualTo("no open gui");
        assertThat(f.inputPort.applied).isEmpty();
    }
}
