package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.plugin.escrow.runtime.CustodyTransfer;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.input.InputSlotPort;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustodyTransferActionTest {

    private final CustodyTransferAction action = new CustodyTransferAction(new GuiBridge());

    @Test
    void itParsesSlotAndExactEntrySourcesAndAKeyedDestination() {
        var slot = action.parse(reader(Map.of(
                "source", Map.of("slot", 4),
                "destination", Map.of(
                        "holder", Map.of("kind", "keyed", "key", "vault:${player.uuid}"),
                        "slot", 2),
                "id", "00000000000000000000000000000001",
                "await", "saved")));
        assertThat(slot.source().slot().literal()).isEqualTo(4);
        assertThat(slot.source().entry()).isNull();
        assertThat(slot.destination().holder().kind()).isEqualTo(CustodyTransferAction.Holder.Kind.KEYED);
        assertThat(slot.reach()).isEqualTo(CustodyTransfer.Reach.SAVED);

        var exact = action.parse(reader(Map.of(
                "source", Map.of(
                        "holder", Map.of("kind", "player"),
                        "entry", "${entry.id}"),
                "destination", Map.of(
                        "holder", Map.of("kind", "player", "player", "${args.target}"),
                        "slot", 0))));
        assertThat(exact.source().holder().kind()).isEqualTo(CustodyTransferAction.Holder.Kind.PLAYER);
        assertThat(exact.source().entry().source()).isEqualTo("${entry.id}");
    }

    @Test
    void invalidAndStackCombinationsFailAtLoad() {
        assertThatThrownBy(() -> action.parse(reader(Map.of(
                "source", Map.of("slot", 1, "entry", "x"),
                "destination", Map.of("holder", Map.of("kind", "player"))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of(
                "source", Map.of("entry", "x"),
                "destination", Map.of("holder", Map.of("kind", "player"))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of(
                "source", Map.of("slot", 1),
                "destination", Map.of("holder", Map.of("kind", "stack"))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of(
                "source", Map.of("entry", "00000000000000000000000000000001",
                        "holder", Map.of("key", "vault:1")),
                "destination", Map.of("holder", Map.of("kind", "player"), "slot", 0)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of(
                "source", Map.of("slot", 1),
                "destination", Map.of("holder", Map.of("kind", "player"))))))
                .as("destination slot is part of the runtime form")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader(Map.of(
                "source", Map.of("slot", 1),
                "destination", Map.of(
                        "holder", Map.of("kind", "player", "player", "not a player"),
                        "slot", 0)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itIsSelfDescribedAndGuardsEffects() {
        var description = action.describe();
        assertThat(action.id()).isEqualTo("custody.transfer");
        assertThat(action.takesElse()).isTrue();
        assertThat(description.self().parameters()).extracting(p -> p.id())
                .containsExactly("source", "destination", "id", "await");
        assertThat(description.self().description()).contains("joint durable decision")
                .contains("Stack holders are refused");
        assertThat(description.self().parameters().get(0).semantics().valueKind())
                .isEqualTo("custody-transfer-source");
        assertThat(description.self().parameters().get(1).semantics().valueKind())
                .isEqualTo("custody-transfer-destination");
    }

    @Test
    void anExactEntryFormRunsWithoutAnOpenGui() {
        GuiFixture fixture = new GuiFixture();
        var params = action.parse(reader(Map.of(
                "source", Map.of(
                        "holder", Map.of("kind", "player"),
                        "entry", "00000000000000000000000000000001"),
                "destination", Map.of("holder", Map.of("kind", "player"), "slot", 0))));
        var context = new MapLogicContext(Map.of()).with(Facets.PLAYER, fixture.viewer.player());

        ActionOutcome outcome = new CustodyTransferAction(fixture.bridge)
                .executeResumable(params, context, ReadExecutor.INLINE).toCompletableFuture().join();

        assertThat(outcome).isEqualTo(ActionOutcome.OK);
        assertThat(fixture.inputPort.transfers).hasSize(1);
    }

    @Test
    void aDynamicInvalidKeyFailsSoftBeforeAnyTransferWrite() {
        GuiFixture fixture = new GuiFixture();
        var params = action.parse(reader(Map.of(
                "source", Map.of(
                        "holder", Map.of("kind", "keyed", "key", "${vars.key}"),
                        "entry", "00000000000000000000000000000001"),
                "destination", Map.of("holder", Map.of("kind", "player"), "slot", 0))));
        var context = new MapLogicContext(Map.of("vars", Map.of("key", "Vault:1")))
                .with(Facets.PLAYER, fixture.viewer.player());

        ActionOutcome outcome = new CustodyTransferAction(fixture.bridge)
                .executeResumable(params, context, ReadExecutor.INLINE).toCompletableFuture().join();

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Failed.class,
                failed -> assertThat(failed.reason()).contains("custody key must be canonical"));
        assertThat(fixture.inputPort.transfers).isEmpty();
    }

    @Test
    void recoveryPendingStopsWithoutBecomingARefusalOrRunningAnElse() {
        GuiFixture fixture = new GuiFixture();
        fixture.gui("""
                transfer:
                  title: Transfer
                  container: chest_1
                  behavior:
                    - on: gui.click
                      do:
                        - custody.transfer:
                            source: {slot: 0}
                            destination:
                              holder: {kind: player}
                              slot: 0
                          else:
                            - mark: refused
                        - mark: committed
                """);
        var record = fixture.opener.open(dev.materione.plugin.view.open.OpenRequest.of(
                fixture.viewer, GuiFixture.id("transfer"), Map.of(),
                dev.materione.plugin.view.open.OpenRequest.Cause.API)).record();
        fixture.ran.clear();
        fixture.inputPort.transferResult =
                new InputSlotPort.TransferOutcome.RecoveryPending("owner left; recovery will finish");

        fixture.router.route(new dev.materione.plugin.gui.input.GuiClick(
                fixture.viewer, record, 0, dev.materione.plugin.gui.input.ClickKind.LEFT, 0));

        assertThat(fixture.ran).isEmpty();
    }

    private static ParamReader reader(Map<String, Object> value) {
        return ParamReader.of(value, EngineExpressions.INSTANCE);
    }
}
