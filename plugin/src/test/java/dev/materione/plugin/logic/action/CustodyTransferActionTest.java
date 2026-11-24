package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ParamReader;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.plugin.escrow.runtime.CustodyTransfer;
import dev.materione.plugin.gui.GuiBridge;
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
                "destination", Map.of("holder", Map.of("kind", "player", "player", "${args.target}")))));
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
    }

    private static ParamReader reader(Map<String, Object> value) {
        return ParamReader.of(value, EngineExpressions.INSTANCE);
    }
}
