package dev.materione.plugin.escrow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.plugin.escrow.io.EscrowLedgerStore;
import dev.materione.plugin.escrow.io.LedgerOptions;
import dev.materione.plugin.escrow.io.LedgerState;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.LedgerCodec;
import dev.materione.plugin.escrow.model.LedgerRecord;
import dev.materione.plugin.escrow.model.ExitKind;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.StackBytes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pre-login preload (gui-input-durability.md §5.7): the ledger is in memory before the join handler runs. */
class EscrowLedgerListenerTest {

    @TempDir
    Path tmp;

    private final List<Diagnostic> diagnostics = new CopyOnWriteArrayList<>();
    private ExecutorService pool;
    private EscrowLedgerStore store;
    private static final UUID PLAYER = new UUID(7, 8);

    @BeforeEach
    void open() {
        pool = Executors.newFixedThreadPool(2);
        store = new EscrowLedgerStore(tmp.resolve("escrow"), pool, LedgerOptions.DEFAULTS, System::currentTimeMillis,
                diagnostics::add, line -> {});
    }

    @AfterEach
    void shut() {
        store.close();
        pool.shutdownNow();
    }

    private void seed() throws Exception {
        Files.createDirectories(store.fileOf(PLAYER).getParent());
        Files.write(store.fileOf(PLAYER), LedgerCodec.encode(new Ledger(List.of(new LedgerRecord.Intent(
                new Id128(1, 1), new Id128(2, 2), ExitKind.CONSUME, StackBytes.of(new byte[] {1}).hash16(), 1, 5L)))));
    }

    @Test
    void aLoginThatIsAllowedHasItsLedgerInMemoryWhenThePreloadReturns() throws Exception {
        seed();
        EscrowLedgerListener listener = new EscrowLedgerListener(store, Duration.ofSeconds(5), diagnostics::add);
        assertThat(store.preloaded(PLAYER)).isEmpty();
        listener.preload(PLAYER);
        assertThat(store.preloaded(PLAYER)).get().isInstanceOfSatisfying(LedgerState.Ready.class,
                ready -> assertThat(ready.ledger().hasIntent(new Id128(2, 2))).isTrue());
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void onlyAnAllowedLoginIsPreloaded() {
        for (AsyncPlayerPreLoginEvent.Result result : AsyncPlayerPreLoginEvent.Result.values()) {
            assertThat(EscrowLedgerListener.admitted(result)).as(result.name())
                    .isEqualTo(result == AsyncPlayerPreLoginEvent.Result.ALLOWED);
        }
    }

    @Test
    void aPreloadThatDoesNotFinishInTimeKeepsTheStateAbsentAndReportsIt() {
        EscrowLedgerStore stuck = new EscrowLedgerStore(tmp.resolve("stuck"), task -> {}, LedgerOptions.DEFAULTS,
                System::currentTimeMillis, diagnostics::add, line -> {});
        EscrowLedgerListener listener = new EscrowLedgerListener(stuck, Duration.ofMillis(30), diagnostics::add);
        listener.preload(PLAYER);
        assertThat(stuck.preloaded(PLAYER)).as("the recovery gate stays closed").isEmpty();
        assertThat(diagnostics).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(DiagnosticCodes.ESCROW_STATE_UNREADABLE);
            assertThat(d.message()).contains(PLAYER.toString()).contains("did not finish");
        });
    }
}
