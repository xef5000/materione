package dev.materione.plugin.escrow.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Probe P6 of gui-input-durability.md §11, as far as a unit test can settle it: the read-back of
 * {@code BukkitValues -> materione:gui_escrow} from a {@code <uuid>.dat} that another thread keeps replacing by the
 * server's own two renames (F2). The writer runs as fast as it can; readers read as fast as they can; the properties:
 *
 * <ul>
 *   <li><b>No false acknowledgment.</b> Every {@code gen} a read reports is one the writer had begun writing, and one
 *       it had already <em>committed</em> before the read started or later: a read never goes back before what was on
 *       disk when it began (it would only be a missed ack, but it is not expected, and an older {@code gen} must be
 *       reported as older).
 *   <li><b>The window is real.</b> The reads did meet the gap between the two renames (the retry delayer ran); a probe
 *       that never overlaps the replace proves nothing.
 *   <li><b>A reader is sensitive.</b> A negative control writes the file <em>in place</em>, non-atomically, and the same
 *       reader must reject the torn states (an {@link ReadBack.Unavailable}) while every {@code gen} it does accept is
 *       one that was written.
 * </ul>
 *
 * The real-server half of P6 (the live server's own save under a live autosave) is row H's IT: this test pins the
 * reader against the replace discipline F2 describes, not against a server.
 */
class EscrowReadBackStressTest {

    private final UUID player = UUID.fromString("00000000-0000-4000-8000-0000000000bb");

    @TempDir
    Path dir;

    @Test
    void noReadEverReportsAGenFromTheFutureOrBeforeWhatWasCommitted() throws Exception {
        AtomicLong started = new AtomicLong();
        AtomicLong committed = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger retries = new AtomicInteger();
        AtomicInteger saved = new AtomicInteger();
        AtomicInteger unavailable = new AtomicInteger();
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        PlayerDatFiles.save(dir, player, PlayerDatFiles.account(0), 60);

        EscrowReadBack reader = new EscrowReadBack(() -> List.of(dir), Runnable::run, PlayerDatFiles.KEY, (millis, task) -> {
            retries.incrementAndGet();
            task.run(); // no sleeping: the retry is what is under test, not the delay
        });
        Thread writer = new Thread(() -> {
            try {
                for (long gen = 1; !stop.get(); gen++) {
                    Path temp = Files.createTempFile(dir, "w", ".tmp");
                    Files.write(temp, PlayerDatFiles.gzip(PlayerDatFiles.nbt(PlayerDatFiles.account(gen), 60, true)));
                    started.set(gen);
                    PlayerDatFiles.replace(dir, player, temp);
                    committed.set(gen);
                }
            } catch (IOException | RuntimeException broken) {
                failures.add("writer: " + broken);
            }
        }, "p6-writer");
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Thread thread = new Thread(() -> {
                while (!stop.get()) {
                    long before = committed.get();
                    ReadBack found = reader.read(player).join();
                    long after = started.get();
                    if (found instanceof ReadBack.Saved read) {
                        saved.incrementAndGet();
                        if (read.gen() > after) {
                            failures.add("a gen from the future: " + read.gen() + " > " + after);
                        }
                        if (read.gen() < before) {
                            failures.add("older than committed before the read began: " + read.gen() + " < " + before);
                        }
                    } else {
                        unavailable.incrementAndGet();
                        if (found instanceof ReadBack.NoAccount none) {
                            failures.add("a snapshot without an account: " + none.detail());
                        }
                    }
                }
            }, "p6-reader-" + i);
            readers.add(thread);
        }
        writer.start();
        readers.forEach(Thread::start);
        long deadline = System.nanoTime() + 2_000_000_000L;
        while ((System.nanoTime() < deadline || retries.get() == 0) && System.nanoTime() < deadline + 8_000_000_000L) {
            Thread.sleep(25);
        }
        stop.set(true);
        writer.join();
        for (Thread thread : readers) {
            thread.join();
        }

        System.out.printf("P6 stress: %d reads accepted, %d unavailable, %d retries, %d snapshots written%n",
                saved.get(), unavailable.get(), retries.get(), committed.get());
        assertThat(failures).isEmpty();
        assertThat(committed.get()).as("the writer replaced the file many times").isGreaterThan(50);
        assertThat(saved.get()).as("reads were accepted").isGreaterThan(100);
        assertThat(retries.get()).as("the reads met the gap between the two renames at least once").isGreaterThan(0);
        assertThat(unavailable.get()).as("almost every read is accepted").isLessThan(saved.get() / 20 + 5);
    }

    @Test
    void anInPlaceNonAtomicRewriteIsRejectedNeverMisread() throws Exception {
        Path dat = dir.resolve(player + ".dat");
        Set<Long> written = ConcurrentHashMap.newKeySet();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger saved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        written.add(0L);
        Files.write(dat, PlayerDatFiles.gzip(PlayerDatFiles.nbt(PlayerDatFiles.account(0), 80, true)));

        EscrowReadBack reader = new EscrowReadBack(() -> List.of(dir), Runnable::run, PlayerDatFiles.KEY,
                (millis, task) -> task.run());
        Thread writer = new Thread(() -> {
            try {
                for (long gen = 1; !stop.get(); gen++) {
                    written.add(gen);
                    byte[] bytes = PlayerDatFiles.gzip(PlayerDatFiles.nbt(PlayerDatFiles.account(gen), 80, true));
                    try (FileChannel out = FileChannel.open(dat, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                        for (int at = 0; at < bytes.length; at += 64) {
                            out.write(ByteBuffer.wrap(bytes, at, Math.min(64, bytes.length - at)));
                            Thread.yield();
                        }
                    }
                }
            } catch (IOException | RuntimeException broken) {
                failures.add("writer: " + broken);
            }
        }, "p6-torn-writer");
        Thread consumer = new Thread(() -> {
            while (!stop.get()) {
                ReadBack found = reader.read(player).join();
                if (found instanceof ReadBack.Saved read) {
                    saved.incrementAndGet();
                    if (!written.contains(read.gen())) {
                        failures.add("accepted a gen nobody wrote: " + read.gen());
                    }
                } else {
                    rejected.incrementAndGet();
                }
            }
        }, "p6-torn-reader");
        writer.start();
        consumer.start();
        Thread.sleep(1_500);
        stop.set(true);
        writer.join();
        consumer.join();

        System.out.printf("P6 negative control: %d accepted, %d rejected%n", saved.get(), rejected.get());
        assertThat(failures).isEmpty();
        assertThat(rejected.get()).as("the torn states were seen, and refused").isGreaterThan(0);
    }
}
