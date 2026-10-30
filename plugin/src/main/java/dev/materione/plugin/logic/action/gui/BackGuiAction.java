package dev.materione.plugin.logic.action.gui;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.GuiRun;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.open.OpenResult;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.List;
import java.util.Optional;

/**
 * {@code gui.back}: goes back to the GUI the player left by opening this one. The previous GUI comes
 * back with the arguments it was opened with and the session variables it had (logic.md §3.1); this
 * one is replaced and is not remembered, so going back twice goes two menus back. With nothing to go
 * back to the step fails and the window stays.
 */
public final class BackGuiAction implements ActionType<BackGuiAction.Params> {

    /** The step has no parameters. */
    public record Params() {}

    private final GuiBridge bridge;

    public BackGuiAction(GuiBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String id() {
        return "gui.back";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Back",
                "Returns to the GUI the player came from, with its arguments and session variables",
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
        GuiRun run;
        try {
            run = GuiRun.ofOpenSession(bridge, context);
        } catch (GuiRun.Refused refused) {
            return ActionOutcome.failed(refused.getMessage());
        }
        Optional<GuiSession.Snapshot> previous = run.services().sessions().history(run.viewer().id()).pop();
        if (previous.isEmpty()) {
            return ActionOutcome.failed("nothing to go back to");
        }
        OpenResult result = run.services().opener().open(OpenRequest.back(run.viewer(), previous.get()));
        return result.ok() ? ActionOutcome.OK : ActionOutcome.failed(result.reason());
    }
}
