package dev.materione.plugin.logic.condition;

import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.input.StackView;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;

/**
 * {@code slot_has: {slot: 13}}, {@code slot_has: {slot: 13, item: corpus:gem_red, amount: 2}}: true when an input
 * slot of the GUI this run belongs to holds the item (logic.md §25). With no {@code item}, any item counts; a
 * bare vanilla name reads as {@code minecraft:<name>}. A slot that is not an input slot, an empty slot, or a run
 * with no open GUI is false, so a check never reads a display item.
 */
public final class SlotHasCondition implements ConditionType<SlotHasCondition.Params> {

    /** The largest amount a check may ask for: the same bound the item actions use. */
    static final int MAX_AMOUNT = 2304;

    /**
     * The parsed condition.
     *
     * @param slot the container slot
     * @param item the identity to match, or empty for any item
     * @param amount how many the slot must hold at least
     */
    public record Params(int slot, Optional<String> item, int amount) {

        public Params {
            Objects.requireNonNull(item, "item");
        }
    }

    private final GuiBridge bridge;

    public SlotHasCondition(GuiBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public String id() {
        return "slot_has";
    }

    @Override
    public ConditionDescription describe() {
        return ConditionDescription.of(new SelfDescription("Slot has item",
                "True when an input slot of this GUI holds the item, at least this amount",
                List.of(
                        new ParameterSchema("slot", ParameterType.INTEGER, true, null,
                                "The container slot, from 0 at the top-left", null, null),
                        new ParameterSchema("item", ParameterType.STRING, false, null,
                                "Materione item id, or minecraft:<material>; any item when absent", null, null),
                        new ParameterSchema("amount", ParameterType.INTEGER, false, "1",
                                "How many the slot must hold at least", null, null)),
                "1.0.0"), LogicCategory.GUI);
    }

    @Override
    public Params parse(ParamReader params) {
        int slot = params.requiredInteger("slot", 0, 53);
        Optional<String> item = params.optionalText("item").map(SlotHasCondition::identity);
        int amount = params.integer("amount", 1, 1, MAX_AMOUNT);
        return new Params(slot, item, amount);
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        GuiSession session = context.facet(GuiSession.FACET).orElse(null);
        if (session == null) {
            return false;
        }
        StackView held = bridge.services()
                .map(services -> services.inputs().peek(session, params.slot()))
                .orElse(StackView.EMPTY);
        if (held.empty() || held.amount() < params.amount()) {
            return false;
        }
        return params.item().map(id -> id.equals(held.id())).orElse(true);
    }

    /** The identity an author wrote: a bare vanilla name is {@code minecraft:<name>}, a Materione id is kept. */
    static String identity(String text) {
        String trimmed = text.trim().toLowerCase(Locale.ROOT);
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }
}
