package dev.materione.plugin.logic.condition;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.logic.LogicIds;
import dev.materione.plugin.logic.support.OpenGuis;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.entity.Player;

/**
 * {@code in_gui: shop:menu} or {@code in_gui: {id: shop:menu}}, or bare {@code in_gui: {}} for any GUI
 * (logic.md §19). True when the player has a Materione GUI open, and when given, that GUI's id matches.
 *
 * <p>The open GUI is read through the {@link OpenGuis} port. Until the GUI session runtime (#214) wires
 * it, the port answers "nothing open", so this condition is false everywhere and never throws. An id
 * names a pack's GUI, so it is written qualified ({@code shop:menu}); a bare id means {@code materione:}.
 */
public final class InGuiCondition implements ConditionType<InGuiCondition.Params> {

    /** The GUI id to match, or empty for any open GUI. */
    public record Params(Optional<NamespacedId> id) {}

    private final OpenGuis guis;

    /** @param guis the port that answers which GUI a player has open */
    public InGuiCondition(OpenGuis guis) {
        this.guis = Objects.requireNonNull(guis, "guis");
    }

    @Override
    public String id() {
        return "in_gui";
    }

    @Override
    public ConditionDescription describe() {
        return new ConditionDescription(new SelfDescription("In GUI",
                "True when the player has a Materione GUI open, or the GUI with this id",
                List.of(new ParameterSchema("id", ParameterType.STRING, false, null,
                        "GUI id, e.g. shop:menu; any open GUI when omitted", null, null)),
                "2.0.0"), LogicCategory.GUI, "id");
    }

    @Override
    public Params parse(ParamReader params) {
        Optional<String> text = params.optionalText("id");
        if (text.isEmpty()) {
            return new Params(Optional.empty());
        }
        NamespacedId id = LogicIds.parse(text.get())
                .orElseThrow(() -> new IllegalArgumentException("id must name a GUI, such as shop:menu"));
        return new Params(Optional.of(id));
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return false;
        }
        Optional<NamespacedId> open = guis.openGui(player);
        if (open.isEmpty()) {
            return false;
        }
        return params.id().map(open.get()::equals).orElse(true);
    }
}
