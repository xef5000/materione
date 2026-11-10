package dev.materione.plugin.escrow.io;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What reading a player's saved file back found (gui-input-durability.md §5.4 tier 3, §11 P6): the barrier's
 * acknowledgment, or the reason there is none. Only {@link Saved} can acknowledge anything; the other two mean "not
 * yet", never "assume" (K23).
 */
public sealed interface ReadBack {

    /**
     * The file holds a readable account.
     *
     * @param gen  the account's generation in that snapshot (a {@code u64}; compare unsigned)
     * @param file the file it was read from
     */
    record Saved(long gen, Path file) implements ReadBack {
        public Saved {
            Objects.requireNonNull(file, "file");
        }
    }

    /**
     * A snapshot was read but holds no account the barrier can compare: the player has no plugin data or no
     * {@code gui_escrow} value, or the value is unreadable (a bad CRC, a newer version).
     *
     * @param detail why, for the log
     */
    record NoAccount(String detail) implements ReadBack {
        public NoAccount {
            Objects.requireNonNull(detail, "detail");
        }
    }

    /**
     * No snapshot could be read: the file is absent (or mid-replace and still absent after the retries), unreadable,
     * truncated, not NBT, or changing faster than it could be read.
     *
     * @param detail why, for the log
     */
    record Unavailable(String detail) implements ReadBack {
        public Unavailable {
            Objects.requireNonNull(detail, "detail");
        }
    }
}
