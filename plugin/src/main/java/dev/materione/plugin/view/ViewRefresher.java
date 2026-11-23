package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableDeclaration;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What {@code view.refresh} asks of the runtime (views.md section 3.6): selects viewers and renders each through the
 * scheduler's explicit entry points (section 5.4). A viewer whose owner thread is the calling thread renders at once;
 * any other gets an immediate wake on its own thread. Neither waits for the kind's {@code minInterval}, and either
 * clears the dirty bit so a wake already queued for the viewer does nothing.
 *
 * <p>Selecting by variable uses the live {@link ReadsIndex}, the same one the write observer matches with, so a
 * definition that does not re-render on change ({@code rerender: off}, which is not in the index) is not selected,
 * and the narrowing by holder is the observer's (views.md section 5.3): a {@code player} variable reaches the viewers
 * of that player, a {@code server} variable every viewer of a definition that reads it, a {@code session} variable
 * the one record that holds the instance. A {@code predicate} kind's viewers are its attached records; the candidate
 * re-evaluation of such a kind is the kind's own.
 */
public final class ViewRefresher {

    private final ViewRegistry views;
    private final Supplier<ReadsIndex> index;
    private final ViewScheduler scheduler;
    private final Predicate<UUID> onOwnerThread;

    /**
     * @param index the live reads index, null while nothing is applied
     * @param onOwnerThread whether the calling thread is the player's owner thread (the region thread on Folia)
     */
    public ViewRefresher(ViewRegistry views, Supplier<ReadsIndex> index, ViewScheduler scheduler,
            Predicate<UUID> onOwnerThread) {
        this.views = Objects.requireNonNull(views, "views");
        this.index = Objects.requireNonNull(index, "index");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.onOwnerThread = Objects.requireNonNull(onOwnerThread, "onOwnerThread");
    }

    /**
     * Renders one record: at once on the calling thread when it owns the player, else by an immediate wake.
     *
     * @return false only when the render ran at once and the record was stale or its definition gone
     */
    public boolean refresh(ViewerRecord record) {
        if (onOwnerThread.test(record.player())) {
            return scheduler.refreshNow(record);
        }
        scheduler.refreshSoon(record);
        return true;
    }

    /** Renders every viewer of the definition; returns how many were selected. */
    public int definition(String kind, NamespacedId definition) {
        return refreshAll(views.showing(kind, definition));
    }

    /**
     * Renders the viewers whose definitions read {@code variable}.
     *
     * @param holder the player (a {@code player} variable) or the view instance (a {@code session} variable) the state
     *     belongs to; ignored for {@code server}
     * @return how many viewers were selected
     */
    public int variable(VariableDeclaration variable, UUID holder) {
        ReadsIndex current = index.get();
        ReadsIndex.Reader[] readers = current == null ? null : current.readers(variable.scope(), variable.id());
        if (readers == null) {
            return 0;
        }
        Set<ViewerRecord> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ReadsIndex.Reader reader : readers) {
            switch (variable.scope()) {
                case SERVER -> selected.addAll(views.showing(reader.view().kind(), reader.view().definition()));
                case PLAYER -> views.current(holder, reader.view().kind())
                        .filter(record -> record.isOpen() && record.ref().equals(reader.view()))
                        .ifPresent(selected::add);
                case SESSION -> {
                    ViewerRecord record = views.sessions().record(holder);
                    if (record != null && record.ref().equals(reader.view())) {
                        selected.add(record);
                    }
                }
                default -> { }
            }
        }
        return refreshAll(selected);
    }

    private int refreshAll(Collection<ViewerRecord> records) {
        for (ViewerRecord record : records) {
            refresh(record);
        }
        return records.size();
    }
}
