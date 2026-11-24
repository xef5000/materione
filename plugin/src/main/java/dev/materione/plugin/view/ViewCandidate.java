package dev.materione.plugin.view;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@code predicate} kind's per-player record, from join to quit (views.md section 5.3): it exists even when no
 * definition of the kind is visible to the player, so that a write to a visibility read has something to mark. It
 * holds only the dirty bit; the kind's wake re-evaluates the visibility of every definition for the player. It costs
 * memory, not wakes.
 */
public final class ViewCandidate implements ViewDirty {

    private final UUID player;
    private final String kind;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile long lastAutomatic;

    @Override
    public long lastAutomatic() {
        return lastAutomatic;
    }

    @Override
    public void lastAutomatic(long tick) {
        this.lastAutomatic = tick;
    }

    ViewCandidate(UUID player, String kind) {
        this.player = Objects.requireNonNull(player, "player");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    @Override
    public UUID player() {
        return player;
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public AtomicBoolean dirtyBit() {
        return dirty;
    }
}
