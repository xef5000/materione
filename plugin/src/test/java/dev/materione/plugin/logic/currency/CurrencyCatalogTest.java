package dev.materione.plugin.logic.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The currency catalog and the built-in integrations (logic.md §18): names resolve as the rules say,
 * the built-in money is always there, an integration that is not installed makes its currency
 * unavailable with the reason, and each built-in integration accepts its keys and rejects the rest.
 */
class CurrencyCatalogTest {

    /** An integration that binds to nothing useful; the catalog's resolution is what is under test. */
    private static CurrencyIntegration integration(String id) {
        return new CurrencyIntegration() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public SelfDescription describe() {
                return new SelfDescription(id, id, List.of(), "2.0.0");
            }

            @Override
            public CurrencyBackend bind(ParamReader config) {
                throw new UnsupportedOperationException("not bound in this test");
            }
        };
    }

    private static MaterioneCurrencyCatalog catalog(String defaultCurrency, String... installed) {
        List<String> names = List.of(installed);
        return new MaterioneCurrencyCatalog(
                authored -> names.contains(authored) ? Optional.of(integration(authored)) : Optional.empty(),
                defaultCurrency);
    }

    private static Currency currency(String namespace, String name, String integration) {
        return new Currency(NamespacedId.of(namespace, name), name, "", 2, integration, Map.of());
    }

    @Test
    void theBuiltInMoneyIsAlwaysDefinedAndIsTheDefaultWhenNothingElseIsSet() {
        var catalog = catalog(null);

        assertThat(catalog.all()).extracting(c -> c.id().toString()).containsExactly("materione:money");
        assertThat(catalog.defaultCurrency().id().toString()).isEqualTo("materione:money");
        assertThat(catalog.get(NamespacedId.of("materione", "money"))).isPresent();
    }

    @Test
    void aBareNameResolvesToTheBuiltInFirstThenToTheOneCurrencyOfThatNameAndIsAmbiguousOtherwise() {
        var catalog = catalog(null, "vault", "custom");
        catalog.define(List.of(
                currency("shop", "gems", "custom"),
                currency("arena", "gems", "custom"),
                currency("shop", "coins", "custom")));

        assertThat(catalog.resolve("money")).isPresent();
        assertThat(catalog.resolve("coins").map(c -> c.id().toString())).contains("shop:coins");
        assertThat(catalog.resolve("gems")).isEmpty();
        assertThat(catalog.resolve("arena:gems").map(c -> c.id().toString())).contains("arena:gems");
        assertThat(catalog.resolve("nope")).isEmpty();
        assertThat(catalog.resolve("")).isEmpty();
    }

    @Test
    void aPackCannotDefineIntoTheReservedNamespaceAndTheCatalogDropsSuchACurrencyToo() {
        var catalog = catalog(null);
        catalog.define(List.of(currency("materione", "gold", "custom")));

        assertThat(catalog.all()).extracting(c -> c.id().toString()).containsExactly("materione:money");
    }

    @Test
    void theDefaultSettingNamesTheCurrencyMoneyActionsUseAndFallsBackWhenItIsNotDefined() {
        var catalog = catalog("shop:gems", "custom");
        assertThat(catalog.defaultCurrency().id().toString()).isEqualTo("materione:money");

        catalog.define(List.of(currency("shop", "gems", "custom")));
        assertThat(catalog.defaultCurrency().id().toString()).isEqualTo("shop:gems");
    }

    @Test
    void anUninstalledIntegrationMakesItsCurrencyUnavailableWithTheReason() {
        var catalog = catalog(null);

        var reason = catalog.availability(catalog.defaultCurrency());
        assertThat(reason.available()).isFalse();
        assertThat(reason.reason()).contains("vault").contains("not installed");
        assertThat(catalog.backend(catalog.defaultCurrency())).isEmpty();
    }

    @Test
    void xpAcceptsPointsAndLevelsAndRejectsAnythingElse() {
        var xp = new XpCurrencyIntegration();

        assertThat(xp.id()).isEqualTo("xp");
        assertThat(xp.bind(ParamReader.of(Map.of()))).isNotNull();
        assertThat(xp.bind(ParamReader.of(Map.of("unit", "levels")))).isNotNull();
        assertThatThrownBy(() -> xp.bind(ParamReader.of(Map.of("unit", "gold"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unit must be one of");
    }

    @Test
    void readCapabilityRequiresAnExplicitFoliaDeclaration() {
        var xp = new XpCurrencyIntegration().bind(ParamReader.of(Map.of()));
        assertThat(xp.readCapability().owner()).isEqualTo(Threading.PLAYER_REGION);
        assertThat(xp.readCapability().folia().available()).isTrue();
        assertThat(xp.readCapability().offlineOwner()).isNull();

        var custom = new CustomCurrencyIntegration().bind(ParamReader.of(Map.of(
                "deposit-command", "give", "withdraw-command", "take", "balance-placeholder", "%x%")));
        assertThat(custom.readCapability().owner()).isEqualTo(Threading.GLOBAL);
        assertThat(custom.readCapability().folia().available()).isFalse();
        assertThat(custom.readCapability().folia().reason()).contains("not declared");
        assertThat(custom.readCapability().offlineOwner()).isNull();
    }

    @Test
    void customRequiresTheThreeCommandAndPlaceholderKeys() {
        var custom = new CustomCurrencyIntegration();

        assertThat(custom.id()).isEqualTo("custom");
        assertThatThrownBy(() -> custom.bind(ParamReader.of(Map.of(
                "withdraw-command", "x", "balance-placeholder", "%x%"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("deposit-command is required");
        assertThatThrownBy(() -> custom.bind(ParamReader.of(Map.of(
                "deposit-command", "x", "withdraw-command", "y"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("balance-placeholder is required");
        assertThat(custom.bind(ParamReader.of(Map.of(
                "deposit-command", "gems give {player} {amount}",
                "withdraw-command", "gems take {player} {amount}",
                "balance-placeholder", "%x%")))).isNotNull();
    }

    @Test
    void theBuiltInIntegrationsAreTheXpAndTheCustomOnesWhileVaultIsRegisteredByTheIntegrationsModule() {
        var ids = dev.materione.plugin.logic.BuiltinLogic.currencyIntegrations().stream()
                .map(CurrencyIntegration::id).toList();

        assertThat(ids).containsExactly("xp", "custom").doesNotContain("vault");
    }
}
