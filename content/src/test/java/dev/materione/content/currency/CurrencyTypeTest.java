package dev.materione.content.currency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.provider.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@code currencies/} content type (logic.md §16): a currency parses under its pack's namespace,
 * its own keys are read, the integration's keys are kept as authored, and a malformed file parses to
 * nothing so the pipeline quarantines it.
 */
class CurrencyTypeTest {

    private final CurrencyType type = new CurrencyType();

    private static Map<String, Object> file(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private Optional<Currency> parse(Map<String, Object> definition) {
        return type.parse(NamespacedId.of("shop", "gems"), definition, Map.of())
                .map(object -> ((CurrencyDefinition) object).currency());
    }

    @Test
    void theTypeIsNamedAndLivesInTheCurrenciesDirectory() {
        assertThat(type.name()).isEqualTo("currency");
        assertThat(type.directory()).isEqualTo("currencies");
        assertThat(type.describe().parameters()).extracting(p -> p.id())
                .contains("integration", "symbol", "decimals", "deposit-command", "balance-placeholder");
    }

    @Test
    void aCustomCurrencyParsesWithItsOwnKeysAndKeepsTheIntegrationKeysAsAuthored() {
        var currency = parse(file(
                "integration", "custom",
                "display_name", "Gems",
                "symbol", "G$",
                "decimals", 0,
                "deposit-command", "gems give {player} {amount}",
                "withdraw-command", "gems take {player} {amount}",
                "balance-placeholder", "%coinsengine_balance_raw_gems%")).orElseThrow();

        assertThat(currency.id()).isEqualTo(NamespacedId.of("shop", "gems"));
        assertThat(currency.displayName()).isEqualTo("Gems");
        assertThat(currency.symbol()).isEqualTo("G$");
        assertThat(currency.decimals()).isZero();
        assertThat(currency.integration()).isEqualTo("custom");
        assertThat(currency.config()).containsOnlyKeys("deposit-command", "withdraw-command", "balance-placeholder")
                .containsEntry("balance-placeholder", "%coinsengine_balance_raw_gems%");
    }

    @Test
    void theDefaultsAreTwoDecimalsNoSymbolAndTheIdsNameAsTheDisplayName() {
        var currency = parse(file("integration", "xp")).orElseThrow();

        assertThat(currency.displayName()).isEqualTo("gems");
        assertThat(currency.symbol()).isEmpty();
        assertThat(currency.decimals()).isEqualTo(2);
        assertThat(currency.config()).isEmpty();
    }

    @Test
    void aMalformedCurrencyParsesToNothingSoItIsQuarantined() {
        assertThat(parse(file("display_name", "no integration"))).isEmpty();
        assertThat(parse(file("integration", "  "))).isEmpty();
        assertThat(parse(file("integration", "vault", "decimals", 9))).isEmpty();
        assertThat(parse(file("integration", "vault", "decimals", 1.5))).isEmpty();
        assertThat(parse(file("integration", "vault", "decimals", "2"))).isEmpty();
        assertThat(type.parse(NamespacedId.of("materione", "money"), file("integration", "vault"), Map.of()))
                .isEmpty();
    }

    @Test
    void nothingIsEmittedToThePackBecauseMoneyIsRuntimeState() {
        // compile is a no-op for currencies; it must not throw for any definition
        type.compile(new CurrencyDefinition(new Currency(
                NamespacedId.of("shop", "gems"), "Gems", "", 0, "xp", Map.of())), null, null, null);
    }
}
