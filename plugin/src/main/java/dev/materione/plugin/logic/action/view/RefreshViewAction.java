package dev.materione.plugin.logic.action.view;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ParseRejection;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableRef;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.view.ViewBridge;
import dev.materione.plugin.view.ViewKinds;
import dev.materione.plugin.view.ViewParams;
import dev.materione.plugin.view.ViewRun;
import dev.materione.plugin.view.ViewerRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * {@code view.refresh}: re-renders viewers (views.md §3.6). At most one selector:
 *
 * <ul>
 *   <li><b>none</b>: the viewer this run belongs to, rendered in full: every layer is read again, a list's entries
 *       included. The step fails {@code "no open view"} when that viewer's record is stale or closed, because a flow
 *       must learn that its window is gone.</li>
 *   <li><b>{@code view:}</b>: every viewer of that definition, wherever they are.</li>
 *   <li><b>{@code var:}</b> with an optional {@code holder:}: the viewers whose definitions read that declared variable.
 *       A {@code player} variable names its player with {@code holder:} (a name or a UUID; the run's own player when
 *       omitted); a {@code server} variable has no holder; a {@code session} variable is the run's own view's.</li>
 * </ul>
 *
 * <p>Selecting nobody is {@code OK}; the step fails only on a selector that cannot be resolved. Each viewer renders on
 * its own owner thread, so the action declares {@link Threading#ANY} (views.md §5.6). An explicit refresh ignores the
 * kind's {@code minInterval} and clears the viewer's dirty bit, so a wake already queued does nothing (§5.4).
 */
public final class RefreshViewAction implements ActionType<RefreshViewAction.Params> {

    /**
     * The parsed step. At most one of {@code view} and {@code var} is set; {@code kind} narrows {@code view}, and
     * {@code holder} narrows {@code var}.
     */
    public record Params(String view, String kind, VariableDeclaration var, TemplateText holder) {}

    private final ViewBridge bridge;

    public RefreshViewAction(ViewBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String id() {
        return "view.refresh";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Refresh view",
                "Re-renders the viewers it selects: this flow's own view with no selector, every viewer of a view "
                        + "with view:, or the viewers whose view reads a variable with var:",
                List.of(
                        new ParameterSchema("view", ParameterType.REFERENCE, false, null,
                                "Refresh every viewer of this view: pack:id, or a name in this view's pack", null, null)
                                .withWidget("reference", "view"),
                        new ParameterSchema("kind", ParameterType.STRING, false, null,
                                "The view kind (gui, dialog) when the id is shared by two kinds", null, null),
                        new ParameterSchema("var", ParameterType.STRING, false, null,
                                "Refresh the viewers whose view reads this declared variable: player.<key>, "
                                        + "server.<pack>.<key> or session.<key>", null, null).withWidget("variable", ""),
                        new ParameterSchema("holder", ParameterType.STRING, false, null,
                                "With a player variable: whose viewers, a player name or UUID; the flow's own player "
                                        + "when omitted", null, null)),
                "1.0.0"), LogicCategory.GUI, "view");
    }

    @Override
    public Threading threading() {
        return Threading.ANY;
    }

    @Override
    public Params parse(ParamReader params) {
        if (params.has("key")) {
            throw new IllegalArgumentException("key: is not available yet; select by view: or var:");
        }
        String view = ViewParams.optional(params, "view");
        String kind = ViewParams.optional(params, "kind");
        String var = ViewParams.optional(params, "var");
        String holder = ViewParams.optional(params, "holder");
        if (view != null && var != null) {
            throw new IllegalArgumentException("name one selector: view: or var:, not both");
        }
        if (kind != null && view == null) {
            throw new IllegalArgumentException("kind: narrows view:, which is not given");
        }
        if (holder != null && var == null) {
            throw new IllegalArgumentException("holder: narrows var:, which is not given");
        }
        VariableDeclaration declaration = var == null ? null : declared(params, var);
        if (holder != null && declaration.scope() != VariableScope.PLAYER) {
            throw new IllegalArgumentException("holder: names the player of a player variable; "
                    + declaration.id() + " is a " + declaration.scope().id() + " variable");
        }
        return new Params(view, kind, declaration, holder == null ? null : params.template("holder"));
    }

    private static VariableDeclaration declared(ParamReader params, String written) {
        VariableRef ref;
        try {
            ref = VariableRef.parse(written, params.environment().pack());
        } catch (IllegalArgumentException malformed) {
            throw new ParseRejection(DiagnosticCodes.VARIABLE_UNDECLARED, malformed.getMessage());
        }
        if (!(ref instanceof VariableRef.Persistent persistent)) {
            throw new IllegalArgumentException("var: names a declared player, server or session variable, not the "
                    + "run-local '" + written + "'");
        }
        VariableDeclaration declaration = params.environment().variable(persistent.id()).orElseThrow(
                () -> new ParseRejection(DiagnosticCodes.VARIABLE_UNDECLARED, "'" + persistent.id()
                        + "' is not a declared variable (declare it in a variables file of its pack)"));
        if (persistent.scope() != null && declaration.scope() != persistent.scope()) {
            throw new ParseRejection(DiagnosticCodes.VARIABLE_UNDECLARED, "'" + persistent.id()
                    + "' is declared " + declaration.scope().id() + ", not " + persistent.scope().id());
        }
        if (declaration.scope() == VariableScope.ITEM) {
            throw new IllegalArgumentException("an item variable is not view state: " + declaration.id());
        }
        return declaration;
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        ViewBridge.Services services = bridge.services().orElse(null);
        if (services == null) {
            return ActionOutcome.failed("the view runtime is not available");
        }
        if (params.view() != null) {
            return byView(params, context, services);
        }
        if (params.var() != null) {
            return byVariable(params, context, services);
        }
        return own(context);
    }

    /** The run's own viewer, in full: the stale-record failure is the one every view verb gives. */
    private ActionOutcome own(LogicContext context) {
        try {
            ViewRun run = ViewRun.ofOpenView(bridge, context);
            return run.services().refresher().refresh(run.record())
                    ? ActionOutcome.OK
                    : ActionOutcome.failed("the window is not showing this view");
        } catch (ViewRun.Refused refused) {
            return ActionOutcome.failed(refused.getMessage());
        }
    }

    private static ActionOutcome byView(Params params, LogicContext context, ViewBridge.Services services) {
        ViewerRecord own = context.facet(ViewerRecord.FACET).orElse(null);
        ViewKinds.Resolution resolution = services.kinds().resolve(params.view(),
                own == null ? null : own.definition(), own == null ? null : own.kind(), params.kind());
        if (!resolution.ok()) {
            return ActionOutcome.failed(resolution.problem());
        }
        NamespacedId id = resolution.found().id();
        services.refresher().definition(resolution.found().kind().contentType(), id);
        return ActionOutcome.OK;
    }

    private static ActionOutcome byVariable(Params params, LogicContext context, ViewBridge.Services services) {
        VariableDeclaration variable = params.var();
        UUID holder = null;
        switch (variable.scope()) {
            case PLAYER -> {
                if (params.holder() == null) {
                    Optional<Player> player = context.facet(Facets.PLAYER);
                    if (player.isEmpty()) {
                        return ActionOutcome.failed("event has no player; name one with holder:");
                    }
                    holder = player.get().getUniqueId();
                } else {
                    String named = params.holder().render(context).trim();
                    holder = uuid(named).or(() -> services.views().playerNamed(named)).orElse(null);
                    if (holder == null) {
                        return ActionOutcome.OK; // nobody by that name has a view open: nobody to refresh
                    }
                }
            }
            case SESSION -> {
                ViewerRecord own = context.facet(ViewerRecord.FACET).orElse(null);
                if (own == null) {
                    return ActionOutcome.failed("no open view holds this session variable");
                }
                holder = own.instance();
            }
            default -> { }
        }
        services.refresher().variable(variable, holder);
        return ActionOutcome.OK;
    }

    private static Optional<UUID> uuid(String text) {
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }
}
