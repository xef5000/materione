package dev.materione.plugin.logic.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.CurrencyLookupCapability;
import dev.materione.api.logic.provider.CurrencyReadCapability;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.run.RunReadExecutor;
import dev.materione.behavior.run.RunScheduler;
import dev.materione.plugin.logic.GatewayRunScheduler;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    void undeclaredLookupNeverCallsBindAvailabilityOrRead() {
        var fixture = new ResolvedFixture(false);
        assertThatThrownBy(() -> fixture.reads.readResolved(fixture.catalog, fixture.currency, player,
                backend -> backend.balance(UUID.randomUUID())))
                .isInstanceOf(ReadUnavailableException.class).hasMessageContaining("not declared");
        assertThatThrownBy(() -> fixture.reads.scheduleResolved(fixture.catalog, fixture.currency, player,
                fixture.executor, backend -> backend.balance(UUID.randomUUID())).toCompletableFuture().join())
                .hasCauseInstanceOf(ReadUnavailableException.class);
        assertThat(fixture.calls).isEmpty();
    }

    @Test
    void synchronousWrongLookupOwnerFailsBeforeBinding() {
        var fixture = new ResolvedFixture(true);
        assertThatThrownBy(() -> fixture.reads.readResolved(fixture.catalog, fixture.currency, player,
                backend -> backend.balance(UUID.randomUUID())))
                .isInstanceOf(ReadUnavailableException.class).hasMessageContaining("GLOBAL");
        assertThat(fixture.calls).isEmpty();
    }

    @Test
    void metadataAvailabilityNeverInvokesAnUnprovenOrWrongOwnerLookup() {
        var unknown = new ResolvedFixture(false);
        assertThat(unknown.reads.availabilityResolved(unknown.catalog, unknown.currency).available()).isFalse();
        assertThat(unknown.calls).isEmpty();

        var declared = new ResolvedFixture(true);
        assertThat(declared.reads.availabilityResolved(declared.catalog, declared.currency).available()).isFalse();
        assertThat(declared.calls).isEmpty();
        declared.current = Threading.GLOBAL;
        assertThat(declared.reads.availabilityResolved(declared.catalog, declared.currency)).isEqualTo(Availability.AVAILABLE);
        assertThat(declared.calls).containsExactly("bind:GLOBAL", "availability:GLOBAL");
    }

    @Test
    void resumableLookupReadAndFreshAvailabilityUseTheirDeclaredOwnersInOrder() {
        var fixture = new ResolvedFixture(true);
        var result = fixture.reads.scheduleResolved(fixture.catalog, fixture.currency, player,
                fixture.executor, backend -> backend.balance(UUID.randomUUID())).toCompletableFuture();
        assertThat(result).isNotDone();
        fixture.runNext();
        assertThat(fixture.calls).containsExactly("bind:GLOBAL", "availability:GLOBAL");
        fixture.runNext();
        assertThat(fixture.calls).containsExactly("bind:GLOBAL", "availability:GLOBAL", "read:PLAYER_REGION");
        assertThat(result).isNotDone();
        fixture.runNext();
        assertThat(result.join()).isEqualTo(new EconomyResult.Succeeded(5));
        assertThat(fixture.calls).containsExactly("bind:GLOBAL", "availability:GLOBAL",
                "read:PLAYER_REGION", "availability:GLOBAL");
    }

    private static final class ResolvedFixture implements RunScheduler {
        Threading current = Threading.PLAYER_REGION;
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        final Currency currency = MaterioneCurrencyCatalog.MONEY;
        final CurrencyReads reads = new CurrencyReads(true, new GatewayRunScheduler.ThreadProbe() {
            @Override public boolean ownsEntity(Entity entity) { return current == Threading.PLAYER_REGION; }
            @Override public boolean onGlobalThread() { return current == Threading.GLOBAL; }
        });
        final RunReadExecutor executor = new RunReadExecutor(this, "player");
        final MaterioneCurrencyCatalog catalog;

        ResolvedFixture(boolean declared) {
            CurrencyBackend backend = new CurrencyBackend() {
                @Override public CurrencyReadCapability readCapability() {
                    return new CurrencyReadCapability(Threading.PLAYER_REGION, Availability.AVAILABLE, null);
                }
                @Override public Availability availability() {
                    calls.add("availability:" + current);
                    return Availability.AVAILABLE;
                }
                @Override public EconomyResult balance(UUID player) {
                    calls.add("read:" + current);
                    return new EconomyResult.Succeeded(5);
                }
                @Override public EconomyResult has(UUID player, double amount) { throw new AssertionError(); }
                @Override public EconomyResult withdraw(UUID player, double amount) { throw new AssertionError(); }
                @Override public EconomyResult deposit(UUID player, double amount) { throw new AssertionError(); }
            };
            CurrencyIntegration integration = new CurrencyIntegration() {
                @Override public String id() { return "vault"; }
                @Override public SelfDescription describe() { return new SelfDescription("fake", "fake", List.of(), "2"); }
                @Override public CurrencyLookupCapability lookupCapability() {
                    return declared ? new CurrencyLookupCapability(Threading.GLOBAL, Availability.AVAILABLE)
                            : CurrencyIntegration.super.lookupCapability();
                }
                @Override public CurrencyBackend bind(ParamReader config) {
                    calls.add("bind:" + current);
                    return backend;
                }
            };
            catalog = new MaterioneCurrencyCatalog(name -> Optional.of(integration), null);
        }

        @Override public boolean canRunInline(Object anchor, Threading owner) {
            return owner == Threading.ANY || owner == current;
        }
        @Override public Handle schedule(Object anchor, Threading owner, long delay, Runnable task, Runnable retired) {
            Runnable wrapped = () -> { current = owner; task.run(); };
            tasks.add(wrapped);
            return () -> tasks.remove(wrapped);
        }
        void runNext() { tasks.remove().run(); }
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
