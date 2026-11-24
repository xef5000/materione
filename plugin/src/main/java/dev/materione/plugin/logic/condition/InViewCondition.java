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
import dev.materione.plugin.logic.support.OpenViews;
import dev.materione.plugin.view.ViewParams;
import dev.materione.plugin.view.ViewRef;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;

/**
 * {@code in_view: shop:menu}, {@code in_view: {view: shop:menu, kind: gui}} or bare {@code in_view: {}} for any view
 * (logic.md §22, views.md §3.6). True when the player has a view open, and, when given, one whose definition is
 * {@code view} and whose kind is {@code kind}. A player has at most one view of each kind open, so a GUI and a dialog
 * over it are both open at once and either satisfies the condition.
 *
 * <p>The open views are read through the {@link OpenViews} port, which {@code BuiltinLogic} backs with the view
 * registry. Before the view runtime binds it answers "nothing open", so this condition is false and never throws. A
 * written id is qualified ({@code shop:menu}); a bare name means that name in the pack of the resource that wrote the
 * condition, the way a {@code view:} of an action does.
 */
public final class InViewCondition implements ConditionType<InViewCondition.Params> {

    /** The definition to match, or null for any; the kind to match, or null for any. */
    public record Params(NamespacedId view, String kind) {}

    private final OpenViews views;

    /** @param views the port that answers which views a player has open */
    public InViewCondition(OpenViews views) {
        this.views = Objects.requireNonNull(views, "views");
    }

    @Override
    public String id() {
        return "in_view";
    }

    @Override
    public ConditionDescription describe() {
        return new ConditionDescription(new SelfDescription("In view",
                "True when the player has a view open, or the view with this id, or a view of this kind",
                List.of(new ParameterSchema("view", ParameterType.REFERENCE, false, null,
                        "View id, e.g. shop:menu, or a name in this resource's pack; any open view when omitted", null, null)
                                .withWidget("reference", "view"),
                        new ParameterSchema("kind", ParameterType.STRING, false, null,
                                "The view kind (gui, dialog): only a view of this kind counts", null, null)),
                "1.0.0"), LogicCategory.GUI, "view");
    }

    @Override
    public Params parse(ParamReader params) {
        String written = ViewParams.optional(params, "view");
        NamespacedId view = null;
        if (written != null) {
            try {
                view = written.indexOf(':') >= 0 ? NamespacedId.parse(written)
                        : NamespacedId.of(ownNamespace(params), written);
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException("view must name a view, such as shop:menu");
            }
        }
        return new Params(view, ViewParams.optional(params, "kind"));
    }

    private static String ownNamespace(ParamReader params) {
        String pack = params.environment().pack();
        return pack == null || pack.isBlank() ? "materione" : pack;
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return false;
        }
        for (ViewRef open : views.openViews(player)) {
            if ((params.kind() == null || params.kind().equals(open.kind()))
                    && (params.view() == null || params.view().equals(open.definition()))) {
                return true;
            }
        }
        return false;
    }
}
