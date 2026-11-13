package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.plugin.escrow.runtime.CustodyConsume.Reach;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.gui.input.ClickKind;
import dev.materione.plugin.gui.input.GuiClick;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code custody.consume} (#248; logic.md §36): it parses strictly, describes itself as a guarding step, and runs in a
 * flow against the real GUI runtime over a fake input port. The click-driven tests are the whole path an author writes:
 * what runs after the step is gated by the outcome, and a failure runs the {@code else:} and ends the run.
 */
class ConsumeCustodyActionTest {

    /** A vault whose buttons consume slot 4 plainly, with an effect, and by the slot of the click. */
    private static final String VAULT = """
            vault:
              title: Vault
              container: chest_3
              layers:
                - id: bin
                  type: slots
                  region: container
                  input: true
                  durable: required
                - id: plain
                  type: button
                  x: 8
                  y: 18
                  width: 16
                  height: 16
                - id: paid
                  type: button
                  x: 26
                  y: 18
                  width: 16
                  height: 16
                - id: here
                  type: button
                  x: 44
                  y: 18
                  width: 16
                  height: 16
                - id: chancy
                  type: button
                  x: 62
                  y: 18
                  width: 16
                  height: 16
              behavior:
                - on: gui.click
                  layer: plain
                  do:
                    - custody.consume: {slot: 4}
                      else:
                        - mark: refused
                    - mark: consumed
                - on: gui.click
                  layer: paid
                  do:
                    - custody.consume: {slot: 4, await: saved}
                    - mark: paid
                - on: gui.click
                  layer: here
                  do:
                    - custody.consume: "${click.slot}"
                    - mark: here
            """;

    private GuiFixture f;
    private GuiSession vault;
    private final ConsumeCustodyAction action = new ConsumeCustodyAction(new dev.materione.plugin.gui.GuiBridge());

    @BeforeEach
    void open() {
        f = new GuiFixture();
        f.gui(VAULT);
        vault = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("vault"), Map.of(), OpenRequest.Cause.API)).session();
        f.ran.clear();
    }

    private static ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE);
    }

    private void click(int slot) {
        f.router.route(new GuiClick(f.viewer, vault, slot, ClickKind.LEFT, 0));
    }

    // ---- the contract: parse, describe -----------------------------------------------------------------------------

    @Test
    void itParsesASlotAndHowFarTheExitMustGet() {
        assertThat(action.parse(reader(Map.of("slot", 4))).reach()).isEqualTo(Reach.DECIDED);
        assertThat(action.parse(reader(Map.of("slot", 4, "await", "saved"))).reach()).isEqualTo(Reach.SAVED);
        assertThat(action.parse(reader(Map.of("slot", 4, "await", "DECIDED"))).reach()).isEqualTo(Reach.DECIDED);
        assertThat(action.parse(reader(Map.of("slot", "${slot.index}"))).slot().template())
                .as("a template names the slot of the event").isNotNull();
    }

    @Test
    void itRejectsWhatItCannotRunSoTheStepIsSkippedAtLoad() {
        assertThatThrownBy(() -> action.parse(reader(Map.of()))).as("a slot is required")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of("slot", 54)))).as("past any chest")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of("slot", -1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of("slot", 1.5)))).as("a count is whole")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of("slot", 4, "await", "later"))))
                .as("an unknown await").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of("slot", "four")))).as("neither a number nor a template")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itDescribesItselfAsAGuardingPrimitiveNotAFeature() {
        var description = action.describe();

        assertThat(action.id()).isEqualTo("custody.consume");
        assertThat(action.takesElse()).as("a failure ends the run, so an effect after it is never reached without it").isTrue();
        assertThat(action.threading()).isEqualTo(Threading.PLAYER_REGION);
        assertThat(description.shorthand()).isEqualTo("slot");
        assertThat(description.self().parameters()).extracting(p -> p.id()).containsExactly("slot", "await");
        assertThat(description.self().parameters().get(1).allowedValues()).containsExactly("decided", "saved");
        assertThat(description.self().displayName() + description.self().description()).doesNotContainIgnoringCase("trash");
    }

    @Test
    void itRunsOnlyInsideAFlowBecauseItWaitsForTheLedger() {
        ActionOutcome outcome = action.execute(action.parse(reader(Map.of("slot", 4))),
                GuiContexts.build(f.viewer, vault, Map.of()));

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(f.inputPort.consumed).isEmpty();
    }

    // ---- in a flow ---------------------------------------------------------------------------------------------------

    @Test
    void aPlainConsumeLetsTheFlowContinueOnceItIsDecided() {
        click(0);

        assertThat(f.inputPort.consumed).containsExactly("4:decided");
        assertThat(f.ran).containsExactly("mark:consumed");
    }

    @Test
    void anEffectStepWaitsForTheProofAndNeverRunsBeforeIt() {
        f.inputPort.consumeResult = new CompletableFuture<>();

        click(1);

        assertThat(f.inputPort.consumed).containsExactly("4:saved");
        assertThat(f.ran).as("the effect is parked behind the proof").isEmpty();

        f.inputPort.consumeResult.complete(Optional.empty());

        assertThat(f.ran).containsExactly("mark:paid");
    }

    @Test
    void anAbandonedProofEndsTheRunSoTheEffectNeverRuns() {
        f.inputPort.consumeResult = new CompletableFuture<>();
        click(1);

        f.inputPort.consumeResult.complete(Optional.of("the consume is decided, but its save was not confirmed"));

        assertThat(f.ran).as("the step after the consume did not run").isEmpty();
    }

    @Test
    void aRefusedConsumeRunsTheElseAndEndsTheRun() {
        f.inputPort.refuse = "slot 4 is busy";

        click(0);

        assertThat(f.ran).as("the else ran; the step after the consume did not").containsExactly("mark:refused");
    }

    @Test
    void theSlotMayBeTheSlotOfTheEventThroughATemplate() {
        click(2);

        assertThat(f.inputPort.consumed).as("${click.slot} is the clicked slot, here the button's").containsExactly("2:decided");
        assertThat(f.ran).containsExactly("mark:here");
    }

    @Test
    void aSlotThatIsNotANumberFailsTheStepNotTheServer() {
        ActionOutcome outcome = failsWith(Map.of("slot", "${gui.id}"));

        assertThat(((ActionOutcome.Failed) outcome).reason()).contains("whole number");
    }

    private ActionOutcome failsWith(Map<String, Object> params) {
        var parsed = action.parse(reader(params));
        var context = GuiContexts.build(f.viewer, vault, Map.of());
        return new ConsumeCustodyAction(bridge()).executeResumable(parsed, context,
                dev.materione.api.logic.ReadExecutor.INLINE).toCompletableFuture().join();
    }

    private dev.materione.plugin.gui.GuiBridge bridge() {
        return f.bridge;
    }
}
