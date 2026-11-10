package dev.materione.plugin.escrow.io;

import dev.materione.plugin.escrow.model.AccountCodec;
import dev.materione.plugin.escrow.model.AccountRead;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The read-back of tier 3 (gui-input-durability.md §5.4, §11 P6; #247 row B): reads the account out of
 * {@code <uuid>.dat} on the IO executor and reports the {@code gen} it found. It is the acknowledgment the platform
 * does not give ({@code Player.saveData()} is {@code void}, F1): a snapshot that cannot be read back is "not yet".
 *
 * <h2>Reading a file the server may replace</h2>
 *
 * The server writes a temporary file and replaces {@code <uuid>.dat} by two renames ({@code .dat} to {@code .dat_old},
 * the temporary file to {@code .dat}; F2), so a reader can meet three states, none of which this class mistakes for an
 * acknowledgment:
 *
 * <ul>
 *   <li><b>The old file</b>, opened before the replace: a complete older snapshot. Its {@code gen} is what it is: an
 *       older {@code gen} acknowledges less, never more.
 *   <li><b>No {@code .dat}</b>, between the two renames: retried with short delays ({@link #RETRY_MILLIS}). If it is
 *       still absent at the last attempt the {@code .dat_old} is read, which is exactly the file the server's own
 *       {@code load} falls back to (F2): the snapshot a boot would see right now.
 *   <li><b>A file that fails to read</b> (a sharing violation, a truncated stream, a bad gzip trailer, a damaged NBT
 *       tree): retried, then {@link ReadBack.Unavailable}. {@link PlayerDatReader} consumes the whole stream, so the
 *       gzip CRC and the NBT structure are both verified before a value is believed, and the account itself carries its
 *       own CRC32 ({@link AccountCodec}).
 * </ul>
 *
 * After a successful read the file's size, modification time and identity are compared with what they were before it:
 * a file replaced during the read is read again at once, so the {@code gen} reported is the newest one on disk when
 * the read ended and not an older one that happened to be open.
 *
 * <p>The directories are asked at each read: a server keeps its player files in {@code playerdata/} (Paper 1.21.4) or
 * {@code players/data/} (26.x); when both hold the player's file the most recently modified one is read.
 */
public final class EscrowReadBack implements SnapshotReader {

    /** The delays between the attempts of one read; so five attempts and about 420 ms at the very most. */
    static final long[] RETRY_MILLIS = {20, 50, 100, 250};

    /** The delays of {@link #awaitAtLeast}: the quit save lands within a moment, a slow disk within seconds. */
    static final long[] POLL_MILLIS = {1_000, 3_000, 10_000, 30_000};

    /** Schedules a task after a delay (the task then runs on the IO executor). A seam for tests. */
    @FunctionalInterface
    public interface Delayer {
        void after(long millis, Runnable task);
    }

    private final Supplier<List<Path>> directories;
    private final Executor io;
    private final Delayer delayer;
    private final String key;

    /**
     * @param directories the folders that may hold {@code <uuid>.dat}, asked at each read (cheap, any thread)
     * @param io          where all file work runs: {@code task -> gateway.executeAsync(plugin, task)}
     * @param key         the persistent-data key of the account, {@code materione:gui_escrow}
     */
    public EscrowReadBack(Supplier<List<Path>> directories, Executor io, String key) {
        this(directories, io, key, (millis, task) ->
                CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS, io).execute(task));
    }

    /** With an explicit delayer (tests). */
    public EscrowReadBack(Supplier<List<Path>> directories, Executor io, String key, Delayer delayer) {
        this.directories = directories;
        this.io = io;
        this.key = key;
        this.delayer = delayer;
    }

    @Override
    public CompletableFuture<ReadBack> read(UUID player) {
        List<Path> dirs = List.copyOf(directories.get());
        CompletableFuture<ReadBack> result = new CompletableFuture<>();
        try {
            io.execute(() -> attempt(player, dirs, 0, result));
        } catch (RuntimeException rejected) {
            result.complete(new ReadBack.Unavailable("the IO executor refused the read: " + rejected));
        }
        return result;
    }

    @Override
    public CompletableFuture<OptionalLong> awaitAtLeast(UUID player, long minGen) {
        CompletableFuture<OptionalLong> result = new CompletableFuture<>();
        poll(player, minGen, 0, result);
        return result;
    }

    private void poll(UUID player, long minGen, int round, CompletableFuture<OptionalLong> result) {
        if (round >= POLL_MILLIS.length) {
            result.complete(OptionalLong.empty());
            return;
        }
        try {
            delayer.after(POLL_MILLIS[round], () -> read(player).whenComplete((found, failed) -> {
                if (found instanceof ReadBack.Saved saved && Long.compareUnsigned(saved.gen(), minGen) >= 0) {
                    result.complete(OptionalLong.of(saved.gen()));
                } else {
                    poll(player, minGen, round + 1, result);
                }
            }));
        } catch (RuntimeException rejected) {
            result.complete(OptionalLong.empty());
        }
    }

    // ---- one read, on an IO thread ------------------------------------------------------------------------------

    private void attempt(UUID player, List<Path> dirs, int attempt, CompletableFuture<ReadBack> result) {
        boolean last = attempt >= RETRY_MILLIS.length;
        Outcome outcome;
        try {
            outcome = readOnce(player, dirs, last);
        } catch (RuntimeException unexpected) {
            outcome = Outcome.retry("unexpected: " + unexpected);
        }
        if (outcome.found != null) {
            result.complete(outcome.found);
        } else if (last) {
            result.complete(new ReadBack.Unavailable(outcome.why));
        } else if (outcome.immediate) {
            attempt(player, dirs, attempt + 1, result);
        } else {
            try {
                delayer.after(RETRY_MILLIS[attempt], () -> attempt(player, dirs, attempt + 1, result));
            } catch (RuntimeException rejected) {
                result.complete(new ReadBack.Unavailable(outcome.why));
            }
        }
    }

    private Outcome readOnce(UUID player, List<Path> dirs, boolean last) {
        Path file = newest(dirs, player + ".dat");
        if (file == null) {
            if (!last) {
                return Outcome.retry("no " + player + ".dat (the server may be replacing it)");
            }
            file = newest(dirs, player + ".dat_old"); // what the server's own load falls back to (F2)
            if (file == null) {
                return Outcome.retry("no player file for " + player);
            }
        }
        BasicFileAttributes before;
        Optional<byte[]> value;
        try {
            before = Files.readAttributes(file, BasicFileAttributes.class);
            value = PlayerDatReader.readValue(file, key);
        } catch (NoSuchFileException vanished) {
            return Outcome.retry(file.getFileName() + " vanished while it was read (replaced)");
        } catch (IOException unreadable) {
            return Outcome.retry(file.getFileName() + " could not be read: " + unreadable);
        }
        if (!last && changed(file, before)) {
            return Outcome.again(); // replaced during the read: read the newer one
        }
        if (value.isEmpty()) {
            return Outcome.done(new ReadBack.NoAccount(file.getFileName() + " holds no " + key));
        }
        return switch (AccountCodec.read(value.get())) {
            case AccountRead.Readable readable -> Outcome.done(new ReadBack.Saved(readable.account().gen(), file));
            case AccountRead.Unreadable unreadable -> Outcome.done(new ReadBack.NoAccount(
                    "the account in " + file.getFileName() + " is unreadable (" + unreadable.reason() + ")"));
            case AccountRead.Absent absent -> Outcome.done(new ReadBack.NoAccount("no account value"));
        };
    }

    private static boolean changed(Path file, BasicFileAttributes before) {
        try {
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
            return after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime())
                    || !Objects.equals(after.fileKey(), before.fileKey());
        } catch (IOException gone) {
            return true; // replaced (and not yet there again): read again
        }
    }

    /** The most recently modified existing {@code name} among the directories, or null. */
    private static Path newest(List<Path> dirs, String name) {
        Path best = null;
        FileTime bestTime = null;
        for (Path dir : dirs) {
            Path candidate = dir.resolve(name);
            try {
                FileTime time = Files.readAttributes(candidate, BasicFileAttributes.class).lastModifiedTime();
                if (best == null || time.compareTo(bestTime) > 0) {
                    best = candidate;
                    bestTime = time;
                }
            } catch (IOException absent) {
                // not in this directory
            }
        }
        return best;
    }

    /** One attempt's result: a finding, or a reason to try again (at once, or after the next delay). */
    private record Outcome(ReadBack found, String why, boolean immediate) {

        static Outcome done(ReadBack found) {
            return new Outcome(found, "", false);
        }

        static Outcome retry(String why) {
            return new Outcome(null, why, false);
        }

        static Outcome again() {
            return new Outcome(null, "the file changed while it was read", true);
        }
    }
}
