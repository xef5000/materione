package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Cancellation;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;

/**
 * {@code cancel: true}: cancels the underlying game event after the actions run. {@code cancel:
 * false} is accepted and does nothing, so a flow can keep a disabled cancel in place while
 * editing. The request is applied by the bridge once the chain completes, never inline.
 */
public final class CancelAction implements ActionType<CancelAction.Params> {

    /** The parsed step. */
    public record Params(boolean cancel) {}

    @Override
    public String id() {
        return "cancel";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Cancel the event",
                "Cancels the underlying game event after the actions run",
                List.of(new ParameterSchema("value", ParameterType.BOOLEAN, false, "true",
                        "Cancel when true", null, null)),
                "2.0.0"), LogicCategory.FLOW, "value");
    }

    @Override
    public Threading threading() {
        return Threading.ANY;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(params.bool("value", true));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        if (!params.cancel()) {
            return ActionOutcome.OK;
        }
        var cancellation = context.facet(Cancellation.FACET);
        if (cancellation.isEmpty()) {
            return ActionOutcome.failed("this event cannot be cancelled");
        }
        cancellation.get().request();
        return ActionOutcome.OK;
    }
}
