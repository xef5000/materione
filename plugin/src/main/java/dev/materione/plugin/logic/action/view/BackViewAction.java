package dev.materione.plugin.logic.action.view;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.view.ViewBridge;
import dev.materione.plugin.view.ViewKind;
import dev.materione.plugin.view.ViewRun;
import dev.materione.plugin.view.ViewerRecord;
import dev.materione.plugin.view.open.OpenRequest;
import dev.materione.plugin.view.open.OpenResult;
import java.util.List;
import java.util.Optional;

/**
 * {@code view.back}: goes back to the view the player left by opening this one, whatever its kind (a GUI, then a
 * dialog, then back to the GUI; views.md §3.6). The previous view comes back with the arguments it was opened with and
 * the session values it had (logic.md §3.1); this one is replaced and is not remembered, so going back twice goes two
 * views back. With nothing to go back to the step fails and the window stays.
 */
public final class BackViewAction implements ActionType<BackViewAction.Params> {

    /** The step has no parameters. */
    public record Params() {}

    private final ViewBridge bridge;

    public BackViewAction(ViewBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String id() {
        return "view.back";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Back",
                "Returns to the view the player came from, with its arguments and session values",
                List.of(), "1.0.0"), LogicCategory.GUI);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params();
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        ViewRun run;
        try {
            run = ViewRun.ofOpenView(bridge, context);
        } catch (ViewRun.Refused refused) {
            return ActionOutcome.failed(refused.getMessage());
        }
        Optional<ViewerRecord.Snapshot> previous = run.services().views().history(run.viewer().id()).pop();
        if (previous.isEmpty()) {
            return ActionOutcome.failed("nothing to go back to");
        }
        Optional<ViewKind> kind = run.services().kinds().of(previous.get().kind());
        if (kind.isEmpty()) {
            return ActionOutcome.failed("the " + previous.get().kind() + " view kind is not available");
        }
        OpenResult result = kind.get().open(OpenRequest.back(run.viewer(), previous.get()));
        return result.ok() ? ActionOutcome.OK : ActionOutcome.failed(result.reason());
    }
}
