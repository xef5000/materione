package dev.materione.plugin.logic.function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import dev.materione.api.logic.variable.GuiSessionPort;
import dev.materione.api.logic.variable.VariablesApi;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.integrations.variables.CurrencyPlaceholderBridge;
import dev.materione.integrations.variables.VariablePlaceholders;
import dev.materione.plugin.logic.currency.MaterioneCurrencyCatalog;
import dev.materione.plugin.logic.currency.CurrencyReads;
import dev.materione.plugin.logic.GatewayRunScheduler;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.CurrencyReadCapability;
import dev.materione.api.logic.provider.CurrencyLookupCapability;
import dev.materione.behavior.run.RunReadExecutor;
import dev.materione.behavior.run.RunScheduler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Collection;
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

    private static final class QueuedScheduler implements RunScheduler {
        Threading current = Threading.GLOBAL;
        Threading scheduled;
        Runnable task;
        @Override public boolean canRunInline(Object anchor, Threading owner) {
            return owner == Threading.ANY || owner == current;
        }
        @Override public Handle schedule(Object anchor, Threading owner, long delay, Runnable task, Runnable retired) {
            this.scheduled = owner;
            this.task = task;
            return () -> this.task = null;
        }
        void run() {
            current = scheduled;
            task.run();
        }
    }

    /** The catalog the functions read, with one integration bound to a fixed balance. */
    private static final class Providers implements LogicProviders {
        final MaterioneCurrencyCatalog currencies;

        Providers(double balance) {
            this(balance, null);
        }

        Providers(double balance, CurrencyReadCapability capability) {
            CurrencyIntegration integration = new CurrencyIntegration() {
                @Override
                public String id() {
                    return "vault";
                }

                @Override
                public CurrencyLookupCapability lookupCapability() {
                    return capability == null ? CurrencyIntegration.super.lookupCapability()
                            : new CurrencyLookupCapability(Threading.ANY, Availability.AVAILABLE);
                }

                @Override
                public SelfDescription describe() {
                    return new SelfDescription("fake", "fake", List.of(), "2.0.0");
                }

                @Override
                public CurrencyBackend bind(ParamReader config) {
                    return new CurrencyBackend() {
                        @Override
                        public CurrencyReadCapability readCapability() {
                            return capability == null ? CurrencyBackend.super.readCapability() : capability;
                        }

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
    void formattedPlaceholderDelegatesTheExplicitCurrencyToTheExistingFormatter() {
        var providers = new Providers(0);
        var format = new FormatCurrencyFunction(providers);
        var context = new MapLogicContext(Map.of());
        VariablesApi unusedVariables = (VariablesApi) Proxy.newProxyInstance(VariablesApi.class.getClassLoader(),
                new Class<?>[] {VariablesApi.class}, (proxy, method, args) -> {
                    throw new AssertionError("unexpected variable access");
                });
        var bridge = new CurrencyPlaceholderBridge() {
            @Override
            public Collection<NamespacedId> currencies() {
                return providers.currencies().all().stream().map(Currency::id).toList();
            }

            @Override
            public Balance read(NamespacedId currency, UUID viewer, boolean formatted) {
                double amount = -1250.5;
                String text = formatted ? (String) format.call(List.of(amount, currency.toString()), context) : null;
                return new Balance(new EconomyResult.Succeeded(amount), text);
            }
        };
        var resolver = new VariablePlaceholders(unusedVariables, () -> GuiSessionPort.NONE, bridge,
                diagnostic -> { throw new AssertionError(diagnostic); });
        assertThat(resolver.resolve("balance_shop_dollars_formatted", ALEX, name -> Optional.empty()))
                .isEqualTo("-$1,250.50");
        assertThat(resolver.resolve("balance_shop_gems_formatted", ALEX, name -> Optional.empty()))
                .isEqualTo("-G$1,251");
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

    @Test
    void foliaRejectsAnUndeclaredThirdPartyProviderEvenOnTheGlobalThread() {
        var probe = new GatewayRunScheduler.ThreadProbe() {
            @Override public boolean ownsEntity(org.bukkit.entity.Entity entity) { return true; }
            @Override public boolean onGlobalThread() { return true; }
        };
        var balance = new BalanceFunction(new Providers(42), new CurrencyReads(true, probe));
        var context = new MapLogicContext(Map.of()).with(Facets.PLAYER, player(ALEX));

        assertThatThrownBy(() -> balance.call(List.of("shop:gems"), context))
                .isInstanceOf(ReadUnavailableException.class).hasMessageContaining("not declared");
    }

    @Test
    void resumableBalanceHopsToTheDeclaredOwnerAndReadsOnlyAfterTheHop() {
        var scheduler = new QueuedScheduler();
        var probe = new GatewayRunScheduler.ThreadProbe() {
            @Override public boolean ownsEntity(org.bukkit.entity.Entity entity) {
                return scheduler.current == Threading.PLAYER_REGION;
            }
            @Override public boolean onGlobalThread() { return scheduler.current == Threading.GLOBAL; }
        };
        var provider = new Providers(42,
                new CurrencyReadCapability(Threading.PLAYER_REGION, Availability.AVAILABLE, null));
        var balance = new BalanceFunction(provider, new CurrencyReads(true, probe));
        var context = new MapLogicContext(Map.of()).with(Facets.PLAYER, player(ALEX));
        var executor = new RunReadExecutor(scheduler, "player");
        var result = balance.callResumable(List.of("shop:gems"), context, executor).toCompletableFuture();

        assertThat(result).isNotDone();
        scheduler.run();
        assertThat(result.join()).isEqualTo(42.0);
        assertThat(scheduler.scheduled).isEqualTo(Threading.PLAYER_REGION);
    }

    @Test
    void aCurrencyRemovedBeforeTheScheduledReadDoesNotUseTheStaleBackend() {
        var scheduler = new QueuedScheduler();
        var probe = new GatewayRunScheduler.ThreadProbe() {
            @Override public boolean ownsEntity(org.bukkit.entity.Entity entity) { return true; }
            @Override public boolean onGlobalThread() { return false; }
        };
        var provider = new Providers(42,
                new CurrencyReadCapability(Threading.PLAYER_REGION, Availability.AVAILABLE, null));
        var balance = new BalanceFunction(provider, new CurrencyReads(true, probe));
        var context = new MapLogicContext(Map.of()).with(Facets.PLAYER, player(ALEX));
        var result = balance.callResumable(List.of("shop:gems"), context,
                new RunReadExecutor(scheduler, "player")).toCompletableFuture();
        provider.currencies.define(List.of());
        scheduler.run();
        assertThatThrownBy(result::join).hasCauseInstanceOf(ReadUnavailableException.class)
                .hasMessageContaining("binding changed");
    }
}
