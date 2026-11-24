package dev.materione.plugin.view;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Something a write can mark as needing a render: a viewer record, or a {@code predicate} kind's per-player candidate
 * (views.md section 5.4). One dirty bit, set by compare-and-set, so a hundred writes in a tick wake it once. Setting
 * the bit touches no viewer-local state and is safe from any thread.
 */
public sealed interface ViewDirty permits ViewerRecord, ViewCandidate {

    /** The player whose render the bit asks for. */
    UUID player();

    /** The view kind (content type name) whose render the bit asks for. */
    String kind();

    /** The bit itself. */
    AtomicBoolean dirtyBit();

    /**
     * Marks this dirty.
     *
     * @return true only for the transition from clean to dirty: the one caller that must schedule the wake
     */
    default boolean markDirty() {
        return dirtyBit().compareAndSet(false, true);
    }

    /** Clears the bit; a render does it before it reads state, so a write during the render marks it again. */
    default void clearDirty() {
        dirtyBit().set(false);
    }

    /** The tick of the last automatic render, 0 before any: what a kind's {@code minInterval} is counted from. */
    long lastAutomatic();

    /** Records the tick of an automatic render; called only by the scheduler, on the owner thread. */
    void lastAutomatic(long tick);

    /** Whether a write is waiting to be rendered. */
    default boolean isDirty() {
        return dirtyBit().get();
    }
}
