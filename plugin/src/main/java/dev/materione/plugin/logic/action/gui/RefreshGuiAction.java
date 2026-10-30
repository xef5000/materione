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
import java.util.List;

/**
 * {@code gui.refresh}: re-renders the window of the GUI this run belongs to from the GUI as it is live
 * now, in place, without closing it: the session, its arguments and its variables are untouched. A flow
 * uses it after changing something the window shows.
 */
public final class RefreshGuiAction implements ActionType<RefreshGuiAction.Params> {

    /** The step has no parameters. */
    public record Params() {}

    private final GuiBridge bridge;

    public RefreshGuiAction(GuiBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String id() {
        return "gui.refresh";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Refresh GUI",
                "Re-renders the open GUI in place", List.of(), "1.0.0"), LogicCategory.GUI);
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
        try {
            GuiRun run = GuiRun.ofOpenSession(bridge, context);
            return run.services().refresh().refresh(run.viewer(), run.session())
                    ? ActionOutcome.OK
                    : ActionOutcome.failed("the window is not showing this GUI");
        } catch (GuiRun.Refused refused) {
            return ActionOutcome.failed(refused.getMessage());
        }
    }
}
