package dev.materione.plugin.logic.action.view;

import dev.materione.api.NamespacedId;
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
import dev.materione.plugin.view.ViewBridge;
import dev.materione.plugin.view.ViewKind;
import dev.materione.plugin.view.ViewKinds;
import dev.materione.plugin.view.ViewRun;
import dev.materione.plugin.view.ViewerRecord;
import java.util.List;
import java.util.Optional;

/**
 * {@code view.close}: closes the view this run belongs to, or, with {@code view:}, the view of that definition the
 * player has open (views.md §3.6). The record ends with reason {@code action}, the kind fires its own close trigger,
 * and the view history is dropped. A run whose view is already gone fails with "no open view" (logic.md §5.2): the
 * flow's later steps still run, because runs belong to the player.
 *
 * <p>With {@code view:} the step closes only if that definition is the one shown, so a rule on an item can close "the
 * shop" and leave whatever else the player has open alone; it fails when the player is not looking at it.
 */
public final class CloseViewAction implements ActionType<CloseViewAction.Params> {

    /** The step's only parameter: the view to close if it is the one shown; null for the run's own view. */
    public record Params(String view) {}

    private final ViewBridge bridge;

    public CloseViewAction(ViewBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String id() {
        return "view.close";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Close view",
                "Closes the view this flow belongs to, or the named view if it is the one the player has open",
                List.of(new ParameterSchema("view", ParameterType.REFERENCE, false, null,
                        "Close only if this view is the one shown: pack:id, or a name in this view's pack; "
                                + "the flow's own view when omitted", null, null)
                        .withWidget("reference", "view")),
                "1.0.0"), LogicCategory.GUI, "view");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(params.has("view") ? params.text("view") : null);
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        try {
            if (params.view() == null) {
                ViewRun run = ViewRun.ofOpenView(bridge, context);
                return close(run, run.record());
            }
            ViewRun run = ViewRun.ofPlayer(bridge, context);
            ViewerRecord own = run.record();
            ViewKinds.Resolution resolution = run.services().kinds().resolve(
                    params.view(), own == null ? null : own.definition(), own == null ? null : own.kind());
            if (!resolution.ok()) {
                return ActionOutcome.failed(resolution.problem());
            }
            NamespacedId id = resolution.found().id();
            Optional<ViewerRecord> shown = run.services().views()
                    .current(run.viewer().id(), resolution.found().kind().contentType())
                    .filter(record -> record.definition().equals(id) && record.isOpen());
            return shown.isPresent() ? close(run, shown.get()) : ActionOutcome.failed("view " + id + " is not open");
        } catch (ViewRun.Refused refused) {
            return ActionOutcome.failed(refused.getMessage());
        }
    }

    private static ActionOutcome close(ViewRun run, ViewerRecord record) {
        Optional<ViewKind> kind = run.services().kinds().of(record.kind());
        if (kind.isEmpty()) {
            return ActionOutcome.failed("no open view");
        }
        return kind.get().close(run.viewer(), record) ? ActionOutcome.OK : ActionOutcome.failed("no open view");
    }
}
