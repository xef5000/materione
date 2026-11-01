package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.CurrencyLookupCapability;
import dev.materione.api.logic.provider.CurrencyReadCapability;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.logic.currency.CurrencyReads;
import dev.materione.plugin.logic.currency.MaterioneCurrencyCatalog;
import dev.materione.plugin.logic.variable.CurrencyPlaceholderReads;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class CurrencyPlaceholderRegistryConcurrencyTest {
    @Test
    void registrationAndOtherReadsProceedDuringProviderCallAndSameFactoryReplacementIsRejected() throws Exception {
        var registry = new LogicRegistryImpl<>(new RegistryService(), RegistryKeys.CURRENCY_INTEGRATIONS,
                CurrencyIntegration.class, "currency integration", CurrencyIntegration::id, CurrencyIntegration::describe);
        Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "getName" -> "test";
                    default -> throw new AssertionError("unexpected plugin call " + method.getName());
                });
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        CurrencyIntegration integration = new CurrencyIntegration() {
            @Override public String id() { return "test"; }
            @Override public SelfDescription describe() { return new SelfDescription("test", "test", List.of(), "1"); }
            @Override public CurrencyLookupCapability lookupCapability() {
                return new CurrencyLookupCapability(Threading.ANY, Availability.AVAILABLE);
            }
            @Override public CurrencyBackend bind(ParamReader config) {
                return new CurrencyBackend() {
                    @Override public CurrencyReadCapability readCapability() {
                        return new CurrencyReadCapability(Threading.ANY, Availability.AVAILABLE, Threading.ANY);
                    }
                    @Override public boolean stableOfflineIdentityRead() { return true; }
                    @Override public Object readBindingIdentity() { return this; }
                    @Override public EconomyResult balance(UUID player) {
                        if (calls.incrementAndGet() == 1) {
                            entered.countDown();
                            awaitRelease(release);
                        }
                        return new EconomyResult.Succeeded(5);
                    }
                    @Override public EconomyResult has(UUID player, double amount) { throw new UnsupportedOperationException(); }
                    @Override public EconomyResult withdraw(UUID player, double amount) { throw new UnsupportedOperationException(); }
                    @Override public EconomyResult deposit(UUID player, double amount) { throw new UnsupportedOperationException(); }
                };
            }
        };
        var registration = registry.register(owner, NamespacedId.of("materione", "test"), integration);
        var catalog = new MaterioneCurrencyCatalog(registry::resolve, null);
        var id = NamespacedId.of("shop", "gold");
        catalog.define(List.of(new Currency(id, "Gold", "$", 2, "test", Map.of())));
        LogicProviders providers = (LogicProviders) Proxy.newProxyInstance(LogicProviders.class.getClassLoader(),
                new Class<?>[] {LogicProviders.class}, (proxy, method, args) -> {
                    if (method.getName().equals("currencies")) return catalog;
                    throw new AssertionError("unexpected provider access");
                });
        var probe = new GatewayRunScheduler.ThreadProbe() {
            @Override public boolean ownsEntity(Entity entity) { return false; }
            @Override public boolean onGlobalThread() { return false; }
        };
        var bridge = new CurrencyPlaceholderReads(providers, registry, new CurrencyReads(true, probe),
                probe, uuid -> null, true);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var first = pool.submit(() -> bridge.read(id, UUID.randomUUID(), true));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var replacement = pool.submit(() -> {
                registration.close();
                return registry.register(owner, NamespacedId.of("materione", "test"), integration);
            });
            var replacementHandle = replacement.get(2, TimeUnit.SECONDS);
            var second = pool.submit(() -> bridge.read(id, UUID.randomUUID(), true));
            assertThat(second.get(2, TimeUnit.SECONDS).formatted()).isEqualTo("$5.00");
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).result()).isInstanceOf(EconomyResult.Failed.class);
            replacementHandle.close();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
