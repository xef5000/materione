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
 * {@code gui.close}: closes the window of the GUI this run belongs to. The session ends with reason
 * {@code action}, {@code gui.close} fires, and the back stack is dropped. A run whose window is already
 * gone fails with "no open gui" (logic.md §5.2): the flow's later steps still run, because runs belong
 * to the player.
 */
public final class CloseGuiAction implements ActionType<CloseGuiAction.Params> {

    /** The step has no parameters. */
    public record Params() {}

    private final GuiBridge bridge;

    public CloseGuiAction(GuiBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String id() {
        return "gui.close";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Close GUI",
                "Closes the GUI this flow belongs to", List.of(), "1.0.0"), LogicCategory.GUI);
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
            return run.services().lifecycle().closeByAction(run.viewer(), run.session())
                    ? ActionOutcome.OK
                    : ActionOutcome.failed("no open gui");
        } catch (GuiRun.Refused refused) {
            return ActionOutcome.failed(refused.getMessage());
        }
    }
}
