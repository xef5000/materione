package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.escrow.runtime.CustodyConsume;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.GuiRun;
import dev.materione.plugin.logic.support.Quantity;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@code custody.consume: {slot, await}}: consumes the items the engine holds in custody at one slot, atomically
 * (#248; logic.md §36; gui-input-durability.md §5.4, §12 row X). Custody is what a {@code durable: required} input layer
 * puts a stack in; this action is its exit, and the only way a stack leaves custody for good. It is a primitive
 * (D12): it names a capability (consume, held), works for any slot of any GUI that has one, and composes with every other
 * step. The "trash slot" is the recipe {@code trash_slot}: a rule on {@code gui.slot_change} whose flow is
 * {@code wait: 1}, then this action.
 *
 * <pre>{@code
 * - custody.consume: { slot: "${slot.index}" }                 # consume what the slot holds; the flow goes on once decided
 * - custody.consume: { slot: 4, await: saved }                 # ...and only once a saved snapshot is read back from disk
 * - give_money: 100                                            #    so this effect can never outlive a rolled-back consume
 * }</pre>
 *
 * <p><b>The guarantee.</b> The consume goes through the tier-2 exit: the held entry freezes, the decision is appended to
 * the ledger (the decision point), the entry is deleted and the slot cleared, and a crash at any instant leaves the stack
 * consumed exactly once or still the player's, never both and never neither. {@code await: decided} (the default) does
 * not wait for a save: the flow continues when the consume is decided and applied (milliseconds), and no
 * {@code Player.saveData()} is requested. {@code await: saved} registers the exit as one that carries an effect: the
 * flow's next steps run only after the barrier has read back a saved snapshot that contains the consume, which is what
 * makes it safe to pay out, run a command or hand over a reward afterwards (the one synchronous save D8's exception admits,
 * rate-limited per player).
 *
 * <p><b>It guards what follows.</b> Like {@code prompt}, the step runs as a {@code require} around itself
 * ({@link ActionType#takesElse()}): when it fails, the {@code else:} steps run and the run ends, so an effect written
 * after it is never reached without the consume. It fails when the slot is not an input slot of the run's open GUI, is
 * empty, belongs to a {@code durable: best_effort} layer (such a slot is not in custody; use
 * {@code gui.slot_item clear} there), is busy (another exit is in flight), when the platform lacks checkpoint support or
 * the player's gate is shut ({@code MTR-0100}), when the decision cannot be recorded ({@code MTR-0102}: the stack went
 * back to the player), and, for {@code await: saved}, when the proof never comes (the player leaves, the plugin stops,
 * no snapshot within the barrier's give-up time: the consume stands, the effect does not run).
 */
public final class ConsumeCustodyAction implements ActionType<ConsumeCustodyAction.Params> {

    /** The highest window slot a chest-shaped GUI has. */
    private static final int LAST_SLOT = 53;

    /**
     * The parsed step.
     *
     * @param slot the window slot: a number, or a template that renders to one ({@code ${slot.index}})
     * @param reach how far the exit must get before the flow continues
     */
    public record Params(Quantity slot, CustodyConsume.Reach reach) {

        public Params {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(reach, "reach");
        }
    }

    private final GuiBridge bridge;

    public ConsumeCustodyAction(GuiBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public String id() {
        return "custody.consume";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Consume held items",
                "Consumes the items held in custody at a slot of this GUI (a durable: required input slot), atomically. "
                        + "The flow continues once the consume is decided, or, with await: saved, only once a saved "
                        + "snapshot is read back from disk, so an effect after it cannot outlive a rolled-back consume. "
                        + "A failure runs its else: and ends the run",
                List.of(
                        new ParameterSchema("slot", ParameterType.INTEGER, true, null,
                                "The container slot whose held stack is consumed; a template such as ${slot.index} "
                                        + "names the slot of the event", null, null),
                        new ParameterSchema("await", ParameterType.ENUM, false, "decided",
                                "decided: continue once the consume is applied (no save is requested). "
                                        + "saved: continue only once a saved snapshot containing it is read back",
                                List.of("decided", "saved"), null)),
                "1.0.0"), LogicCategory.PLAYER, "slot");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public boolean takesElse() {
        return true;
    }

    @Override
    public Params parse(ParamReader params) {
        String await = params.choice("await", "decided", List.of("decided", "saved"));
        return new Params(Quantity.requiredCount(params, "slot", 0, LAST_SLOT),
                CustodyConsume.Reach.valueOf(await.toUpperCase(Locale.ROOT)));
    }

    /** Only the flow interpreter runs it: it waits there. Reached only by a caller that bypasses it. */
    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return ActionOutcome.failed("custody.consume waits for the ledger and runs only inside a flow");
    }

    /**
     * Starts the consume on the player's thread and returns the stage the run parks on: it completes with
     * {@link ActionOutcome#OK} when the consume reached what the step asked for, or with the reason it did not.
     */
    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        return params.slot().resolveResumable(context, reads)
                .thenCompose(slot -> reads.on(threading(), () -> start(slot, params.reach(), context)))
                .thenCompose(started -> started);
    }

    private CompletionStage<ActionOutcome> start(Quantity slot, CustodyConsume.Reach reach, LogicContext context) {
        OptionalInt whole = slot.wholeValue(context);
        if (whole.isEmpty() || whole.getAsInt() < 0 || whole.getAsInt() > LAST_SLOT) {
            return failed("slot must be a whole number from 0 to " + LAST_SLOT);
        }
        GuiRun run;
        try {
            run = GuiRun.ofOpenSession(bridge, context);
        } catch (GuiRun.Refused refused) {
            return failed(refused.getMessage());
        }
        return run.services().inputs().consume(run.viewer().player(), run.session(), whole.getAsInt(), reach)
                .thenApply(reason -> reason.<ActionOutcome>map(ActionOutcome::failed).orElse(ActionOutcome.OK));
    }

    private static CompletionStage<ActionOutcome> failed(String reason) {
        return CompletableFuture.completedFuture(ActionOutcome.failed(reason));
    }
}
