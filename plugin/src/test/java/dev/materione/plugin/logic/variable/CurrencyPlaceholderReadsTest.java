package dev.materione.plugin.logic.variable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.LogicRegistration;
import dev.materione.api.logic.LogicRegistry;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyCatalog;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.CurrencyLookupCapability;
import dev.materione.api.logic.provider.CurrencyReadCapability;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.logic.provider.PermissionProvider;
import dev.materione.api.logic.provider.ServerTransfer;
import dev.materione.api.logic.variable.GuiSessionPort;
import dev.materione.api.logic.variable.VariablesApi;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.api.registry.SelfDescription;
import dev.materione.integrations.variables.VariablePlaceholders;
import dev.materione.plugin.logic.GatewayRunScheduler.ThreadProbe;
import dev.materione.plugin.logic.currency.CurrencyReads;
import dev.materione.plugin.logic.currency.CustomCurrencyIntegration;
import dev.materione.plugin.logic.currency.MaterioneCurrencyCatalog;
import dev.materione.plugin.logic.currency.XpCurrencyIntegration;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class CurrencyPlaceholderReadsTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final NamespacedId GOLD = NamespacedId.of("shop", "gold");

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ID;
                    case "getLevel" -> 12;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("unexpected player access " + method.getName());
                });
    }

    private static final class Registry implements LogicRegistry<CurrencyIntegration> {
        CurrencyIntegration integration;
        long generation = 1;

        @Override public LogicRegistration register(Plugin owner, NamespacedId id, CurrencyIntegration type) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<CurrencyIntegration> get(NamespacedId id) {
            return resolve(id.toString());
        }
        @Override public Optional<CurrencyIntegration> resolve(String name) {
            return integration != null && (integration.id().equals(name)
                    || ("materione:" + integration.id()).equals(name)) ? Optional.of(integration) : Optional.empty();
        }
        @Override public List<RegistryEntry<CurrencyIntegration>> entries() {
            return integration == null ? List.of() : List.of(new RegistryEntry<>(NamespacedId.of("materione", integration.id()),
                    integration, new SelfDescription("test", "test", List.of(), "1"), "test", generation));
        }
    }

    private static final class Probe implements ThreadProbe {
        boolean global = true;
        boolean owns = true;
        @Override public boolean ownsEntity(Entity entity) { return owns; }
        @Override public boolean onGlobalThread() { return global; }
    }

    private static final class Fixture {
        final Registry registry = new Registry();
        final Probe probe = new Probe();
        final Player player = player();
        Player online = player;
        final MaterioneCurrencyCatalog catalog = new MaterioneCurrencyCatalog(registry::resolve, null);
        final List<Diagnostic> diagnostics = new ArrayList<>();
        int binds;
        int reads;
        double amount = 1250.5;
        Runnable duringRead = () -> { };
        boolean providerThrows;
        boolean available = true;
        int availabilityCalls;
        int lookupCalls;
        int onlineCalls;
        CurrencyLookupCapability lookup = CurrencyLookupCapability.unsupported();
        CurrencyReadCapability capability = CurrencyReadCapability.unsupported(Threading.GLOBAL);
        boolean stableOnline;
        boolean stableOffline;
        Object bindingIdentity = new Object();
        final CurrencyIntegration integration = new CurrencyIntegration() {
            @Override public String id() { return "test"; }
            @Override public SelfDescription describe() { return new SelfDescription("test", "test", List.of(), "1"); }
            @Override public CurrencyLookupCapability lookupCapability() {
                lookupCalls++;
                return lookup;
            }
            @Override public CurrencyBackend bind(ParamReader config) {
                binds++;
                return new CurrencyBackend() {
                    @Override public CurrencyReadCapability readCapability() { return capability; }
                    @Override public boolean stableOnlineIdentityRead() { return stableOnline; }
                    @Override public boolean stableOfflineIdentityRead() { return stableOffline; }
                    @Override public Object readBindingIdentity() { return bindingIdentity; }
                    @Override public Availability availability() {
                        availabilityCalls++;
                        return available ? Availability.AVAILABLE : Availability.unavailable("provider stopped");
                    }
                    @Override public EconomyResult balance(UUID player) {
                        reads++;
                        duringRead.run();
                        if (providerThrows) throw new IllegalStateException("provider error");
                        return new EconomyResult.Succeeded(amount);
                    }
                    @Override public EconomyResult has(UUID player, double amount) { throw new UnsupportedOperationException(); }
                    @Override public EconomyResult withdraw(UUID player, double amount) { throw new UnsupportedOperationException(); }
                    @Override public EconomyResult deposit(UUID player, double amount) { throw new UnsupportedOperationException(); }
                };
            }
        };
        final LogicProviders providers = new LogicProviders() {
            @Override public CurrencyCatalog currencies() { return catalog; }
            @Override public PermissionProvider permissions() { throw new UnsupportedOperationException(); }
            @Override public Optional<ServerTransfer> transfer() { return Optional.empty(); }
            @Override public ProviderRegistration setPermissions(Plugin owner, PermissionProvider value) {
                throw new UnsupportedOperationException();
            }
            @Override public ProviderRegistration setTransfer(Plugin owner, ServerTransfer value) {
                throw new UnsupportedOperationException();
            }
        };

        Fixture() {
            registry.integration = integration;
            define("$", 2);
        }

        void define(String symbol, int decimals) {
            catalog.define(List.of(new Currency(GOLD, "Gold", symbol, decimals, "test", Map.of())));
        }

        MaterionePlaceholders expansion(boolean folia) {
            var bridge = new CurrencyPlaceholderReads(providers, registry,
                    new CurrencyReads(folia, probe), probe, id -> {
                        onlineCalls++;
                        return online;
                    }, folia);
            VariablesApi variables = (VariablesApi) Proxy.newProxyInstance(VariablesApi.class.getClassLoader(),
                    new Class<?>[] {VariablesApi.class}, (proxy, method, args) -> {
                        throw new AssertionError("unexpected variable access");
                    });
            return new MaterionePlaceholders(
                    new VariablePlaceholders(variables, () -> GuiSessionPort.NONE, bridge, diagnostics::add), "1");
        }
    }

    @Test
    void callbackReadsAndFormatsTheLiveExplicitCurrencyAndRechecksProviders() {
        var fixture = new Fixture();
        var expansion = fixture.expansion(false);
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEqualTo("1250.5");
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold_formatted")).isEqualTo("$1,250.50");
        fixture.amount = -3;
        fixture.define("G", 0);
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold_formatted")).isEqualTo("-G3");
        fixture.registry.integration = null;
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEmpty();
        fixture.registry.integration = fixture.integration;
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEqualTo("-3");
        assertThat(expansion.onRequest(fixture.player, "balance_shop_missing")).isNull();
        assertThat(fixture.reads).isEqualTo(4);
    }

    @Test
    void asyncOfflineNullAndUnsupportedRequestsFailBeforeBackendAccess() {
        var fixture = new Fixture();
        var paper = fixture.expansion(false);
        assertThat(paper.onRequest(null, "balance_shop_gold")).isEmpty();
        fixture.probe.global = false;
        assertThat(paper.onRequest(fixture.player, "balance_shop_gold")).isEmpty();
        assertThat(fixture.binds).isZero();
        assertThat(fixture.lookupCalls).isZero();
        assertThat(fixture.onlineCalls).isZero();
        assertThat(fixture.availabilityCalls).isZero();
        fixture.probe.global = true;
        fixture.online = null;
        OfflinePlayer offline = fixture.player;
        assertThat(paper.onRequest(offline, "balance_shop_gold")).isEmpty();
        assertThat(fixture.binds).isEqualTo(1);
        fixture.online = fixture.player;
        fixture.probe.owns = false;
        assertThat(paper.onRequest(fixture.player, "balance_shop_gold")).isEmpty();
        fixture.probe.owns = true;
        assertThat(fixture.expansion(true).onRequest(fixture.player, "balance_shop_gold")).isEmpty();
        assertThat(fixture.binds).isEqualTo(1);
        assertThat(fixture.availabilityCalls).isZero();
        assertThat(fixture.reads).isZero();
    }

    @Test
    void replacementOrReconnectDuringAReadCannotMixBalanceAndNewMetadata() {
        var fixture = new Fixture();
        var expansion = fixture.expansion(false);
        fixture.duringRead = () -> fixture.define("NEW", 0);
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold_formatted")).isEmpty();
        assertThat(fixture.diagnostics).anySatisfy(d -> assertThat(d.message()).contains("binding or player changed"));
        fixture.duringRead = () -> fixture.online = player();
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEmpty();
        fixture.online = fixture.player;
        fixture.duringRead = () -> { };
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold_formatted")).isEqualTo("NEW1,251");
    }

    @Test
    void providerExceptionsAreReportedAndNeverBecomeZero() {
        var fixture = new Fixture();
        fixture.providerThrows = true;
        assertThat(fixture.expansion(false).onRequest(fixture.player, "balance_shop_gold")).isEmpty();
        assertThat(fixture.diagnostics).singleElement().extracting(Diagnostic::message).asString()
                .contains("provider error");
    }

    @Test
    void removingAnIntegrationDuringTheCallbackDiscardsTheOldBalance() {
        var fixture = new Fixture();
        var expansion = fixture.expansion(false);
        fixture.duringRead = () -> fixture.registry.integration = null;
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold_formatted")).isEmpty();
        assertThat(fixture.diagnostics).anySatisfy(d -> assertThat(d.message()).contains("binding or player changed"));
    }

    @Test
    void recursivePlaceholderProvidersFailWithoutRecursionOrCaching() {
        var fixture = new Fixture();
        var expansion = fixture.expansion(false);
        fixture.duringRead = () -> assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEmpty();
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEqualTo("1250.5");
        assertThat(fixture.reads).isEqualTo(1);
        assertThat(fixture.diagnostics).anySatisfy(d -> assertThat(d.message()).contains("already in progress"));
        fixture.duringRead = () -> { };
        fixture.amount = 7;
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEqualTo("7");
    }

    @Test
    void declaredGlobalAndAnyOwnersSupportOnlineAndOfflineReads() {
        var fixture = new Fixture();
        fixture.lookup = new CurrencyLookupCapability(Threading.GLOBAL, Availability.AVAILABLE);
        fixture.capability = new CurrencyReadCapability(Threading.GLOBAL, Availability.AVAILABLE, Threading.GLOBAL);
        fixture.stableOnline = true;
        fixture.stableOffline = true;
        fixture.probe.owns = false;
        var expansion = fixture.expansion(true);
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEqualTo("1250.5");
        fixture.online = null;
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold_formatted")).isEqualTo("$1,250.50");
        fixture.probe.global = false;
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEmpty();
        fixture.lookup = new CurrencyLookupCapability(Threading.ANY, Availability.AVAILABLE);
        fixture.capability = new CurrencyReadCapability(Threading.ANY, Availability.AVAILABLE, Threading.ANY);
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEqualTo("1250.5");
        fixture.online = fixture.player;
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEqualTo("1250.5");
    }

    @Test
    void unavailableCapabilityAndIdentityNeverCallAvailabilityOrBalance() {
        var fixture = new Fixture();
        fixture.lookup = new CurrencyLookupCapability(Threading.ANY, Availability.AVAILABLE);
        var expansion = fixture.expansion(true);
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEmpty();
        assertThat(fixture.availabilityCalls).isZero();
        assertThat(fixture.reads).isZero();
        fixture.capability = new CurrencyReadCapability(Threading.ANY, Availability.AVAILABLE, Threading.ANY);
        fixture.online = null;
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEmpty();
        assertThat(fixture.availabilityCalls).isZero();
        fixture.stableOffline = true;
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEqualTo("1250.5");
    }

    @Test
    void lookupOwnerMustBeProvedBeforeBindingOrAvailability() {
        var fixture = new Fixture();
        fixture.lookup = new CurrencyLookupCapability(Threading.GLOBAL, Availability.AVAILABLE);
        fixture.probe.global = false;
        var expansion = fixture.expansion(true);
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEmpty();
        assertThat(fixture.binds).isZero();
        assertThat(fixture.availabilityCalls).isZero();
        assertThat(fixture.reads).isZero();
    }

    @Test
    void lostAvailabilityDuringReadDiscardsTheBalance() {
        var fixture = new Fixture();
        fixture.duringRead = () -> fixture.available = false;
        assertThat(fixture.expansion(false).onRequest(fixture.player, "balance_shop_gold_formatted")).isEmpty();
        assertThat(fixture.availabilityCalls).isEqualTo(2);
        assertThat(fixture.diagnostics).anySatisfy(d -> assertThat(d.message()).contains("provider stopped"));
    }

    @Test
    void replacingAProviderWhileItRemainsAvailableDiscardsTheBalance() {
        var fixture = new Fixture();
        fixture.duringRead = () -> fixture.bindingIdentity = new Object();
        assertThat(fixture.expansion(false).onRequest(fixture.player, "balance_shop_gold_formatted")).isEmpty();
        assertThat(fixture.diagnostics).anySatisfy(d -> assertThat(d.message()).contains("provider changed"));
    }

    @Test
    void missingOptionalProviderTokenDoesNotRejectAValidRead() {
        var fixture = new Fixture();
        fixture.bindingIdentity = null;
        assertThat(fixture.expansion(false).onRequest(fixture.player, "balance_shop_gold")).isEqualTo("1250.5");
        assertThat(fixture.reads).isEqualTo(1);
        fixture.duringRead = () -> fixture.bindingIdentity = new Object();
        assertThat(fixture.expansion(false).onRequest(fixture.player, "balance_shop_gold")).isEmpty();
    }

    @Test
    void nativeXpUsesTheCapturedPlayerOnItsFoliaOwner() {
        var fixture = new Fixture();
        fixture.registry.integration = new XpCurrencyIntegration();
        fixture.catalog.define(List.of(new Currency(GOLD, "XP", "XP", 2, "xp", Map.of("unit", "levels"))));
        fixture.probe.global = false;
        var expansion = fixture.expansion(true);
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold_formatted")).isEqualTo("XP12.00");
        fixture.probe.owns = false;
        assertThat(expansion.onRequest(fixture.player, "balance_shop_gold")).isEmpty();
    }

    @Test
    void customPlaceholderBackendDoesNotClaimFoliaLookupSafety() {
        assertThat(new CustomCurrencyIntegration().lookupCapability().folia().available()).isFalse();
    }
}
