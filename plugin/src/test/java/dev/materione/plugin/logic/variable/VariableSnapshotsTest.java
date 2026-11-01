package dev.materione.plugin.logic.variable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VariableSnapshotsTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final AtomicReference<PdcVariableStore.Access> access =
            new AtomicReference<>(PdcVariableStore.Access.OWNED);
    private final AtomicInteger containerAccesses = new AtomicInteger();
    private final org.bukkit.persistence.PersistentDataContainer container = VariableTestSupport.fakeContainer();
    private final PdcVariableStore pdc = new PdcVariableStore(player -> {
        containerAccesses.incrementAndGet();
        return Optional.of(container);
    }, player -> access.get());
    private final VariableStore server = new VariableStore() {
        @Override public VariableScope scope() { return VariableScope.SERVER; }
        @Override public Optional<Object> read(VariableDeclaration declaration, UUID player) { return Optional.empty(); }
        @Override public boolean write(VariableDeclaration declaration, UUID player, Object value) { return true; }
    };
    private final VariableService service =
            new VariableService(pdc, server, VariableTestSupport::declarations, diagnostic -> {});

    @Test
    void offOwnerReadsUseOnlyAnAcknowledgedSnapshotAndNeverTouchPdc() {
        access.set(PdcVariableStore.Access.UNOWNED);
        assertThat(service.api().getResult(VariableTestSupport.COINS, PLAYER).diagnostics())
                .singleElement().satisfies(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.VARIABLE_STORE_FAILED));
        assertThatThrownBy(() -> service.bind(PLAYER).stored(VariableTestSupport.COINS_DECL))
                .isInstanceOf(ReadUnavailableException.class);
        assertThat(containerAccesses).hasValue(0);

        access.set(PdcVariableStore.Access.OWNED);
        service.refreshPlayer(PLAYER);
        int acknowledged = containerAccesses.get();
        access.set(PdcVariableStore.Access.UNOWNED);
        assertThat(service.api().get(VariableTestSupport.COINS, PLAYER)).contains(0.0);
        assertThat(containerAccesses).hasValue(acknowledged);
    }

    @Test
    void writesRefreshSnapshotsAndLifecycleInvalidationRejectsStaleValues() {
        var actor = ActorContext.player(PLAYER, "Steve", node -> true);
        assertThat(service.api().set(actor, VariableTestSupport.COINS, PLAYER, 8).ok()).isTrue();
        access.set(PdcVariableStore.Access.UNOWNED);
        assertThat(service.api().get(VariableTestSupport.COINS, PLAYER)).contains(8.0);

        service.invalidatePlayer(PLAYER);
        assertThat(service.api().get(VariableTestSupport.COINS, PLAYER)).isEmpty();
        access.set(PdcVariableStore.Access.OWNED);
        service.refreshPlayer(PLAYER);
        access.set(PdcVariableStore.Access.UNOWNED);
        assertThat(service.api().get(VariableTestSupport.COINS, PLAYER)).contains(8.0);

        service.invalidateSnapshots();
        assertThat(service.api().get(VariableTestSupport.COINS, PLAYER)).isEmpty();
        access.set(PdcVariableStore.Access.OFFLINE);
        assertThat(service.api().get(VariableTestSupport.COINS, PLAYER)).contains(0.0);
        access.set(PdcVariableStore.Access.UNOWNED);
        assertThat(service.api().get(VariableTestSupport.COINS, PLAYER)).isEmpty();
    }

    @Test
    void offOwnerWritesRefuseAndInvalidateThePreviousSnapshot() {
        service.refreshPlayer(PLAYER);
        access.set(PdcVariableStore.Access.UNOWNED);
        var actor = ActorContext.player(PLAYER, "Steve", node -> true);
        assertThat(service.api().set(actor, VariableTestSupport.COINS, PLAYER, 3).ok()).isFalse();
        assertThat(service.api().get(VariableTestSupport.COINS, PLAYER)).isEmpty();
    }
}
