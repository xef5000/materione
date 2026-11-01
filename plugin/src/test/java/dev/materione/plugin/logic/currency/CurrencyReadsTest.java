package dev.materione.plugin.logic.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyReadCapability;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.plugin.logic.GatewayRunScheduler;
import java.lang.reflect.Proxy;
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
