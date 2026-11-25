package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ParamReader;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.plugin.gui.GuiBridge;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustodyTransferReviewReproTest {

    @Test
    void aLiteralNonCanonicalHolderKeyFailsAtContentLoad() {
        var action = new CustodyTransferAction(new GuiBridge());
        Map<String, Object> params = Map.of(
                "source", Map.of(
                        "holder", Map.of("kind", "keyed", "key", "source"),
                        "entry", "00000000000000000000000000000001"),
                "destination", Map.of(
                        "holder", Map.of("kind", "keyed", "key", "Vault:1")));

        assertThatThrownBy(() -> action.parse(ParamReader.of(params, EngineExpressions.INSTANCE)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
