package dev.materione.plugin.logic.action.gui;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.GuiRun;
import dev.materione.plugin.gui.input.SlotOp;
import java.util.List;
import java.util.Objects;

/**
 * {@code gui.slot_item: {slot: 13, op: take}}: applies one operation to the item in an input slot of the GUI
 * this run belongs to (logic.md §4.1, §25): {@code clear} deletes it, {@code take} gives it to the player,
 * {@code set_amount} sets its amount, {@code repair} restores its durability and {@code enchant} adds an
 * enchantment. The GUI must still be open and be the player's (as {@code gui.close} requires); a slot that
 * is not an input slot, an empty slot or an operation the item does not take fails with its reason.
 */
public final class GuiSlotItemAction implements ActionType<GuiSlotItemAction.Params> {

    /** The largest amount {@code set_amount} accepts: the same bound as the item actions. */
    static final int MAX_AMOUNT = 2304;

    /**
     * The parsed step.
     *
     * @param slot the container slot
     * @param op the operation, with its own parameters
     */
    public record Params(int slot, SlotOp op) {

        public Params {
            Objects.requireNonNull(op, "op");
        }
    }

    private final GuiBridge bridge;

    public GuiSlotItemAction(GuiBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public String id() {
        return "gui.slot_item";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Input slot item",
                "Clears, takes, resizes, repairs or enchants the item in an input slot of this GUI",
                List.of(
                        new ParameterSchema("slot", ParameterType.INTEGER, true, null,
                                "The container slot, from 0 at the top-left", null, null),
                        new ParameterSchema("op", ParameterType.ENUM, true, null,
                                "clear, take, set_amount, repair or enchant",
                                List.of("clear", "take", "set_amount", "repair", "enchant"), null),
                        new ParameterSchema("amount", ParameterType.INTEGER, false, null,
                                "set_amount only: the new amount, 0 clears the slot", null, null),
                        new ParameterSchema("enchantment", ParameterType.STRING, false, null,
                                "enchant only: the enchantment's key, for example sharpness", null, null),
                        new ParameterSchema("level", ParameterType.INTEGER, false, "1",
                                "enchant only: the level, at least 1", null, null)),
                "1.0.0"), LogicCategory.GUI);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        int slot = params.requiredInteger("slot", 0, 53);
        String word = params.text("op");
        SlotOp.Kind kind = SlotOp.Kind.of(word.trim().toLowerCase(java.util.Locale.ROOT)).orElseThrow(
                () -> new IllegalArgumentException("op must be clear, take, set_amount, repair or enchant, not '" + word + "'"));
        return new Params(slot, switch (kind) {
            case SET_AMOUNT -> new SlotOp(kind, params.requiredInteger("amount", 0, MAX_AMOUNT), "", 0);
            case ENCHANT -> new SlotOp(kind, 0, params.text("enchantment"), params.integer("level", 1, 1, 255));
            default -> SlotOp.of(kind);
        });
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        try {
            GuiRun run = GuiRun.ofOpenSession(bridge, context);
            return run.services().inputs().apply(run.viewer().player(), run.session(), params.slot(), params.op())
                    .map(ActionOutcome::failed)
                    .orElse(ActionOutcome.OK);
        } catch (GuiRun.Refused refused) {
            return ActionOutcome.failed(refused.getMessage());
        }
    }
}
