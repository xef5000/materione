package dev.materione.plugin.logic.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.Threading;
import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.CurrencyLookupCapability;
import dev.materione.api.logic.provider.CurrencyReadCapability;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.run.RunReadExecutor;
import dev.materione.behavior.run.RunScheduler;
import dev.materione.plugin.logic.GatewayRunScheduler;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class CurrencyReadsTest {
    private boolean entityOwner;
    private boolean globalOwner;
    private final CurrencyReads reads = new CurrencyReads(true, new GatewayRunScheduler.ThreadProbe() {
        @Override public boolean ownsEntity(Entity entity) { return entityOwner; }
        @Override public boolean onGlobalThread() { return globalOwner; }
    });
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[] {Player.class}, (proxy, method, args) -> {
                throw new AssertionError("unexpected player access");
            });
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void unknownGlobalProviderIsRejectedEvenOnTheGlobalThread() {
        globalOwner = true;
        CurrencyBackend unknown = backend(null);
        assertThatThrownBy(() -> reads.read(unknown, player, () -> {
            calls.incrementAndGet();
            return new EconomyResult.Succeeded(5);
        })).isInstanceOf(ReadUnavailableException.class).hasMessageContaining("not declared");
        assertThat(calls).hasValue(0);
    }

    @Test
    void declaredPlayerReadsRunOnlyOnTheEntityOwner() {
        CurrencyBackend xp = backend(new CurrencyReadCapability(Threading.PLAYER_REGION, Availability.AVAILABLE, null));
        assertThatThrownBy(() -> reads.read(xp, player, () -> new EconomyResult.Succeeded(5)))
                .isInstanceOf(ReadUnavailableException.class);
        entityOwner = true;
        assertThat(reads.read(xp, player, () -> new EconomyResult.Succeeded(5)))
                .isEqualTo(new EconomyResult.Succeeded(5));
        assertThatThrownBy(() -> reads.read(xp, player, () -> new EconomyResult.Failed("retired")))
                .isInstanceOf(ReadUnavailableException.class).hasMessageContaining("retired");
    }

    @Test
    void offlineOwnerCanDifferFromOnlineOwner() {
        var capability = new CurrencyReadCapability(Threading.PLAYER_REGION, Availability.AVAILABLE, Threading.GLOBAL);
        assertThat(capability.offlineOwner()).isEqualTo(Threading.GLOBAL);
        CurrencyBackend declared = backend(capability);
        assertThatThrownBy(() -> reads.readOffline(declared, () -> new EconomyResult.Succeeded(5)))
                .isInstanceOf(ReadUnavailableException.class).hasMessageContaining("GLOBAL");
        globalOwner = true;
        assertThat(reads.readOffline(declared, () -> new EconomyResult.Succeeded(5)))
                .isEqualTo(new EconomyResult.Succeeded(5));
        assertThatThrownBy(() -> reads.readOffline(backend(new CurrencyReadCapability(
                Threading.PLAYER_REGION, Availability.AVAILABLE, null)), () -> new EconomyResult.Succeeded(5)))
                .isInstanceOf(ReadUnavailableException.class).hasMessageContaining("offline");
        assertThatThrownBy(() -> new CurrencyReadCapability(
                Threading.GLOBAL, Availability.AVAILABLE, Threading.PLAYER_REGION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void flowLookupRefusesUnknownIntegrationBeforeBindingOrAvailability() {
        var bound = new AtomicInteger();
        var availability = new AtomicInteger();
        CurrencyIntegration integration = integration(CurrencyLookupCapability.unsupported(), bound, availability);
        var catalog = new MaterioneCurrencyCatalog(name -> Optional.of(integration), null);
        Currency currency = new Currency(NamespacedId.of("shop", "gold"), "Gold", "$", 2, "test", Map.of());
        catalog.define(List.of(currency));
        globalOwner = true;
        assertThatThrownBy(() -> reads.readResolved(catalog, currency, player,
                backend -> new EconomyResult.Succeeded(5)))
                .isInstanceOf(ReadUnavailableException.class).hasMessageContaining("lookup");
        assertThat(bound).hasValue(0);
        assertThat(availability).hasValue(0);
    }

    @Test
    void flowReadsHopToLookupOwnerReadOwnerAndBackWithoutBlocking() {
        var bound = new AtomicInteger();
        var availability = new AtomicInteger();
        CurrencyIntegration integration = integration(
                new CurrencyLookupCapability(Threading.GLOBAL, Availability.AVAILABLE), bound, availability);
        var catalog = new MaterioneCurrencyCatalog(name -> Optional.of(integration), null);
        Currency currency = new Currency(NamespacedId.of("shop", "gold"), "Gold", "$", 2, "test", Map.of());
        catalog.define(List.of(currency));
        var tasks = new ArrayDeque<Runnable>();
        var owners = new ArrayDeque<Threading>();
        final Threading[] current = {Threading.PLAYER_REGION};
        RunScheduler scheduler = new RunScheduler() {
            @Override public boolean canRunInline(Object anchor, Threading owner) {
                return owner == Threading.ANY || owner == current[0];
            }
            @Override public Handle schedule(Object anchor, Threading owner, long delay, Runnable task, Runnable retired) {
                owners.add(owner);
                tasks.add(task);
                return () -> { };
            }
        };
        var probe = new GatewayRunScheduler.ThreadProbe() {
            @Override public boolean ownsEntity(Entity entity) { return current[0] == Threading.PLAYER_REGION; }
            @Override public boolean onGlobalThread() { return current[0] == Threading.GLOBAL; }
        };
        var guarded = new CurrencyReads(true, probe);
        var future = guarded.scheduleResolved(catalog, currency, player, new RunReadExecutor(scheduler, "player"),
                backend -> {
                    calls.incrementAndGet();
                    return new EconomyResult.Succeeded(5);
                }).toCompletableFuture();
        assertThat(future).isNotDone();
        assertThat(owners.remove()).isEqualTo(Threading.GLOBAL);
        current[0] = Threading.GLOBAL;
        tasks.remove().run();
        assertThat(bound).hasValue(1);
        assertThat(availability).hasValue(1);
        assertThat(calls).hasValue(0);
        assertThat(owners.remove()).isEqualTo(Threading.PLAYER_REGION);
        current[0] = Threading.PLAYER_REGION;
        tasks.remove().run();
        assertThat(calls).hasValue(1);
        assertThat(future).isNotDone();
        assertThat(owners.remove()).isEqualTo(Threading.GLOBAL);
        current[0] = Threading.GLOBAL;
        tasks.remove().run();
        assertThat(future.join()).isEqualTo(new EconomyResult.Succeeded(5));
        assertThat(availability).hasValue(2);
    }

    @Test
    void metadataAvailabilityNeedsLookupOwnershipAndNeverGuessesAPlayer() {
        var bound = new AtomicInteger();
        var availability = new AtomicInteger();
        CurrencyIntegration integration = integration(
                new CurrencyLookupCapability(Threading.GLOBAL, Availability.AVAILABLE), bound, availability);
        var catalog = new MaterioneCurrencyCatalog(name -> Optional.of(integration), null);
        Currency currency = new Currency(NamespacedId.of("shop", "gold"), "Gold", "$", 2, "test", Map.of());
        catalog.define(List.of(currency));
        assertThat(reads.availabilityResolved(catalog, currency).available()).isFalse();
        assertThat(bound).hasValue(0);
        assertThat(availability).hasValue(0);
        globalOwner = true;
        assertThat(reads.availabilityResolved(catalog, currency).available()).isTrue();
        assertThat(availability).hasValue(1);
        CurrencyIntegration region = integration(
                new CurrencyLookupCapability(Threading.PLAYER_REGION, Availability.AVAILABLE), bound, availability);
        var regionCatalog = new MaterioneCurrencyCatalog(name -> Optional.of(region), null);
        regionCatalog.define(List.of(currency));
        assertThat(reads.availabilityResolved(regionCatalog, currency).available()).isFalse();
        assertThat(availability).hasValue(1);
    }

    private static CurrencyIntegration integration(CurrencyLookupCapability lookup, AtomicInteger binds,
            AtomicInteger availability) {
        return new CurrencyIntegration() {
            @Override public String id() { return "test"; }
            @Override public SelfDescription describe() { return new SelfDescription("test", "test", List.of(), "1"); }
            @Override public CurrencyLookupCapability lookupCapability() { return lookup; }
            @Override public CurrencyBackend bind(ParamReader config) {
                binds.incrementAndGet();
                return new CurrencyBackend() {
                    @Override public CurrencyReadCapability readCapability() {
                        return new CurrencyReadCapability(Threading.PLAYER_REGION, Availability.AVAILABLE, null);
                    }
                    @Override public Availability availability() {
                        availability.incrementAndGet();
                        return Availability.AVAILABLE;
                    }
                    @Override public EconomyResult balance(UUID player) { throw new AssertionError(); }
                    @Override public EconomyResult has(UUID player, double amount) { throw new AssertionError(); }
                    @Override public EconomyResult withdraw(UUID player, double amount) { throw new AssertionError(); }
                    @Override public EconomyResult deposit(UUID player, double amount) { throw new AssertionError(); }
                };
            }
        };
    }

    private static CurrencyBackend backend(CurrencyReadCapability capability) {
        return new CurrencyBackend() {
            @Override public CurrencyReadCapability readCapability() {
                return capability == null ? CurrencyBackend.super.readCapability() : capability;
            }
            @Override public EconomyResult balance(UUID player) { throw new AssertionError(); }
            @Override public EconomyResult has(UUID player, double amount) { throw new AssertionError(); }
            @Override public EconomyResult withdraw(UUID player, double amount) { throw new AssertionError(); }
            @Override public EconomyResult deposit(UUID player, double amount) { throw new AssertionError(); }
        };
    }
}
