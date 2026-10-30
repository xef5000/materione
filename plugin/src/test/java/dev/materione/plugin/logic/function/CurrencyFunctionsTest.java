package dev.materione.plugin.logic.function;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyCatalog;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.logic.provider.PermissionProvider;
import dev.materione.api.logic.provider.ServerTransfer;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.currency.MaterioneCurrencyCatalog;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * {@code format_currency} and {@code balance} (logic.md §18). Formatting is pure over the currency's own
 * definition; balance reads the run's player through the currency's backend and answers null when it
 * cannot.
 */
class CurrencyFunctionsTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");

    /** The catalog the functions read, with one integration bound to a fixed balance. */
    private static final class Providers implements LogicProviders {
        final MaterioneCurrencyCatalog currencies;

        Providers(double balance) {
            CurrencyIntegration integration = new CurrencyIntegration() {
                @Override
                public String id() {
                    return "vault";
                }

                @Override
                public SelfDescription describe() {
                    return new SelfDescription("fake", "fake", List.of(), "2.0.0");
                }

                @Override
                public CurrencyBackend bind(ParamReader config) {
                    return new CurrencyBackend() {
                        @Override
                        public EconomyResult balance(UUID player) {
                            return new EconomyResult.Succeeded(balance);
                        }

                        @Override
                        public EconomyResult has(UUID player, double amount) {
                            return new EconomyResult.Succeeded(balance);
                        }

                        @Override
                        public EconomyResult withdraw(UUID player, double amount) {
                            return new EconomyResult.Succeeded(balance);
                        }

                        @Override
                        public EconomyResult deposit(UUID player, double amount) {
                            return new EconomyResult.Succeeded(balance);
                        }
                    };
                }
            };
            this.currencies = new MaterioneCurrencyCatalog(
                    authored -> "vault".equals(authored) ? Optional.of(integration) : Optional.empty(), null);
            this.currencies.define(List.of(
                    new Currency(NamespacedId.of("shop", "gems"), "Gems", "G$", 0, "vault", Map.of()),
                    new Currency(NamespacedId.of("shop", "dollars"), "Dollars", "$", 2, "vault", Map.of())));
        }

        @Override
        public CurrencyCatalog currencies() {
            return currencies;
        }

        @Override
        public PermissionProvider permissions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ServerTransfer> transfer() {
            return Optional.empty();
        }

        @Override
        public ProviderRegistration setPermissions(org.bukkit.plugin.Plugin owner, PermissionProvider provider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderRegistration setTransfer(org.bukkit.plugin.Plugin owner, ServerTransfer provider) {
            throw new UnsupportedOperationException();
        }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("unexpected player call: " + method.getName());
                });
    }

    @Test
    void formatCurrencyWritesTheSymbolAndTheDecimalsOfTheCurrencyAndPutsTheSignBeforeTheSymbol() {
        var format = new FormatCurrencyFunction(new Providers(0));
        var context = new MapLogicContext(Map.of());

        assertThat(format.call(List.of(1250.0, "shop:gems"), context)).isEqualTo("G$1,250");
        assertThat(format.call(List.of(12.5, "dollars"), context)).isEqualTo("$12.50");
        assertThat(format.call(List.of(-3.0, "shop:gems"), context)).isEqualTo("-G$3");
        assertThat(format.call(List.of(1.0, "nope"), context)).isNull();
        assertThat(format.call(List.of("x", "shop:gems"), context)).isNull();
        assertThat(format.call(List.of(Double.NaN, "shop:gems"), context)).isNull();
    }

    @Test
    void balanceReadsTheRunsPlayersBalanceOfTheNamedCurrency() {
        var balance = new BalanceFunction(new Providers(42));
        var context = new MapLogicContext(Map.of()).with(Facets.PLAYER, player(ALEX));

        assertThat(balance.call(List.of("shop:gems"), context)).isEqualTo(42.0);
        assertThat(balance.call(List.of("nope"), context)).isNull();
    }

    @Test
    void balanceIsNullWithoutAPlayerOrWhenTheCurrencyCannotAnswer() {
        var balance = new BalanceFunction(new Providers(42));

        assertThat(balance.call(List.of("shop:gems"), new MapLogicContext(Map.of()))).isNull();
        assertThat(balance.describe().self().displayName()).isEqualTo("Balance");
        assertThat(new BalanceFunction(new Providers(42)).id()).isEqualTo("balance");
        assertThat(new FormatCurrencyFunction(new Providers(0)).id()).isEqualTo("format_currency");
    }
}
