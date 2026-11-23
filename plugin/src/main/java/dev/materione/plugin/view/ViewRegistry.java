package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every live viewer record, for every view kind (views.md §3.1, §3.8). The registry is the only place that says "this
 * player has this view open"; a window's holder points at its record, and a window whose record is no longer the
 * player's current one is stale and ignored.
 *
 * <p>Indexed by <b>player</b> (the record a player has per kind, the history, the view they had before) and by
 * <b>definition</b> (the records showing a definition, for a live reload and, in row V1, for a write that a view
 * reads). The index by <b>key</b> is reserved for row V5 ({@link #showingKey}): a key binding does not exist until
 * keyed state does (views.md §6.2).
 *
 * <p>One record per kind per player: vanilla shows one container and one dialog at a time, a platform fact
 * (views.md §3.8). A new record of a kind displaces that kind's previous one; a record of another kind coexists with
 * it (a dialog over a container). The view history spans kinds.
 *
 * <p>Region-safe by construction: the state is concurrent maps keyed by player and definition, each player's entry
 * is only ever driven from that player's thread, and the cross-thread reads (the live refresh walking every record,
 * a placeholder, the write observer of row V1) take snapshots. A record is added by {@link #begin} and removed by
 * {@link #end}, which are idempotent and race-free against each other.
 */
public final class ViewRegistry {

    private final ConcurrentHashMap<UUID, PlayerViews> players = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ViewRef, Set<ViewerRecord>> byDefinition = new ConcurrentHashMap<>();
    private volatile MutationGuard guard = MutationGuard.NONE;

    /**
     * Installs the guard every record begun from now on reports its mutations to ({@link MutationGuard}); the default
     * checks nothing. The ban test installs a recording one.
     */
    public void guard(MutationGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /** The player's current record: the most recently begun of those still open, of any kind. */
    public Optional<ViewerRecord> current(UUID player) {
        PlayerViews entry = players.get(player);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.latest());
    }

    /** The player's current record of one kind ({@code gui}), if they have one. */
    public Optional<ViewerRecord> current(UUID player, String kind) {
        PlayerViews entry = players.get(player);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.byKind.get(kind));
    }

    /**
     * The view the player had open before the current one: the last record that stopped being current, by being
     * replaced or by closing. Empty when they have had none since they joined.
     */
    public Optional<ViewRef> last(UUID player) {
        PlayerViews entry = players.get(player);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.last);
    }

    /** The player's view history (created empty on first use). */
    public ViewHistory history(UUID player) {
        return players.computeIfAbsent(player, ignored -> new PlayerViews()).history;
    }

    /**
     * Makes {@code next} the player's current record of its kind.
     *
     * @return the record of the same kind it displaced, if the player had one; the caller ends it
     */
    public Optional<ViewerRecord> begin(ViewerRecord next) {
        next.guard(guard);
        PlayerViews entry = players.computeIfAbsent(next.player(), ignored -> new PlayerViews());
        synchronized (entry) {
            ViewerRecord previous = entry.byKind.put(next.kind(), next);
            if (previous != null) {
                entry.last = previous.ref();
                unindex(previous);
            }
            index(next);
            return Optional.ofNullable(previous);
        }
    }

    /**
     * Ends {@code record} for {@code reason}: marks it ended, and removes it if it is still the player's current record
     * of its kind. A reason that does not keep history also clears the view history once the player has no record
     * left.
     *
     * @return true when this call ended it; false when it had already ended (the caller then fires nothing)
     */
    public boolean end(ViewerRecord record, CloseReason reason) {
        boolean ended = record.end(reason);
        PlayerViews entry = players.get(record.player());
        if (entry != null) {
            synchronized (entry) {
                if (entry.byKind.remove(record.kind(), record)) {
                    entry.last = record.ref();
                    unindex(record);
                }
                if (ended && !reason.keepsHistory() && entry.byKind.isEmpty()) {
                    entry.history.clear();
                }
            }
        }
        return ended;
    }

    /** Drops everything remembered about a player who left. */
    public void forget(UUID player) {
        PlayerViews entry = players.remove(player);
        if (entry != null) {
            synchronized (entry) {
                entry.byKind.values().forEach(this::unindex);
            }
        }
    }

    /**
     * The open records of {@code kind} showing {@code definition}, as a snapshot safe to iterate while records end. The
     * index is keyed by kind and id together: a GUI and a dialog may share an id and are different definitions.
     */
    public List<ViewerRecord> showing(String kind, NamespacedId definition) {
        Set<ViewerRecord> showing = byDefinition.get(new ViewRef(kind, definition));
        return showing == null ? List.of() : List.copyOf(showing);
    }

    /**
     * The open records bound to {@code key}. Reserved for row V5 (views.md §6.2): there is no key binding until keyed
     * state exists, so this answers empty for every key.
     */
    public List<ViewerRecord> showingKey(String key) {
        Objects.requireNonNull(key, "key");
        return List.of();
    }

    /** Every open record of every kind, as a snapshot. */
    public List<ViewerRecord> all() {
        var out = new ArrayList<ViewerRecord>();
        for (PlayerViews entry : players.values()) {
            out.addAll(entry.byKind.values());
        }
        return out;
    }

    /** Every open record of one kind, as a snapshot. */
    public List<ViewerRecord> all(String kind) {
        var out = new ArrayList<ViewerRecord>();
        for (PlayerViews entry : players.values()) {
            ViewerRecord record = entry.byKind.get(kind);
            if (record != null) {
                out.add(record);
            }
        }
        return out;
    }

    /** How many players have a view of any kind open. */
    public int openCount() {
        int count = 0;
        for (PlayerViews entry : players.values()) {
            if (!entry.byKind.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Adds inside the map's atomic update, so an {@link #unindex} that empties and drops the same set on another
     * thread cannot leave this record in a set the map no longer holds.
     */
    private void index(ViewerRecord record) {
        byDefinition.compute(record.ref(), (definition, showing) -> {
            Set<ViewerRecord> set = showing != null ? showing : ConcurrentHashMap.<ViewerRecord>newKeySet();
            set.add(record);
            return set;
        });
    }

    private void unindex(ViewerRecord record) {
        byDefinition.computeIfPresent(record.ref(), (definition, showing) -> {
            showing.remove(record);
            return showing.isEmpty() ? null : showing;
        });
    }

    private static final class PlayerViews {
        private final ConcurrentHashMap<String, ViewerRecord> byKind = new ConcurrentHashMap<>();
        private volatile ViewRef last;
        private final ViewHistory history = new ViewHistory();

        /** The most recently begun record still current in its kind. */
        ViewerRecord latest() {
            ViewerRecord best = null;
            for (ViewerRecord record : byKind.values()) {
                if (best == null || record.serial() > best.serial()) {
                    best = record;
                }
            }
            return best;
        }
    }
}
