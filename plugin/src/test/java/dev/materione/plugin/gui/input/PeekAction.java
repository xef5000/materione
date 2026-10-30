package dev.materione.plugin.gui.input;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.SelfDescription;

/** {@code peek}: runs a callback when reached, so a test can observe the world from inside a flow. */
final class PeekAction implements ActionType<String> {

    private final Runnable callback;

    PeekAction(Runnable callback) {
        this.callback = callback;
    }

    @Override
    public String id() {
        return "peek";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(SelfDescription.of("Peek", "Runs a test callback"), LogicCategory.DATA);
    }

    @Override
    public Threading threading() {
        return Threading.ANY;
    }

    @Override
    public String parse(ParamReader params) {
        return "";
    }

    @Override
    public ActionOutcome execute(String params, LogicContext context) {
        callback.run();
        return ActionOutcome.OK;
    }
}
