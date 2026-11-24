package dev.materione.plugin.view;

import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.plugin.logic.variable.VariableWriteObserver;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The write observer (views.md section 5.3): called after every successful write to a player, server or session
 * variable, it looks the family up in the current {@link ReadsIndex} and marks the viewers the write reaches dirty. A
 * miss returns at once, which is every write nothing reads.
 *
 * <p>Runs on the writer's thread. It reads the immutable index, walks the registry's concurrent sets and flips dirty
 * bits; it touches no viewer-local state. Only the transition from clean to dirty calls the {@link ViewWake}, so a
 * hundred writes to one viewer's reads wake it once.
 *
 * <table>
 *   <caption>Who a write marks</caption>
 *   <tr><td>{@code player} P</td><td>the viewers of the reading definitions whose player is P; for a
 *       {@code predicate} kind, P's candidate record</td></tr>
 *   <tr><td>{@code server}</td><td>every viewer of every reading definition; for a {@code predicate} kind, every
 *       candidate for a visibility read and the attached viewers' candidates for a content read</td></tr>
 *   <tr><td>{@code session}</td><td>the one record that holds the instance</td></tr>
 * </table>
 *
 * <p>An item variable wakes only the views bound to the exact subject stack that was written. A run's locals are
 * never state.
 */
public final class ViewChanges implements VariableWriteObserver {

    private static final Logger LOG = Logger.getLogger(ViewChanges.class.getName());
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private final Supplier<ReadsIndex> index;
    private final ViewRegistry views;
    private final ViewKinds kinds;
    private volatile ViewWake wake;

    /**
     * @param index the live index, read on every write (the registries' current {@link ReadsIndex})
     * @param wake what runs when a record or candidate becomes dirty
     */
    public ViewChanges(Supplier<ReadsIndex> index, ViewRegistry views, ViewKinds kinds, ViewWake wake) {
        this.index = Objects.requireNonNull(index, "index");
        this.views = Objects.requireNonNull(views, "views");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.wake = Objects.requireNonNull(wake, "wake");
    }

    /** Replaces what runs when something becomes dirty. */
    public void wake(ViewWake wake) {
        this.wake = Objects.requireNonNull(wake, "wake");
    }

    @Override
    public void written(VariableDeclaration declaration, UUID holder) {
        ReadsIndex current = index.get();
        if (current == null) {
            return;
        }
        ReadsIndex.Reader[] readers = current.readers(declaration.scope(), declaration.id());
        if (readers == null) {
            return;
        }
        switch (declaration.scope()) {
            case PLAYER -> player(readers, holder);
            case SERVER -> server(readers);
            case SESSION -> session(readers, holder);
            case ITEM -> { }
        }
    }

    @Override
    public void stackWritten(VariableDeclaration declaration, StackRef holder) {
        if (holder == null) {
            return;
        }
        ReadsIndex current = index.get();
        if (current == null) {
            return;
        }
        ReadsIndex.Reader[] readers = current.readers(declaration.scope(), declaration.id());
        if (readers == null) {
            return;
        }
        for (ReadsIndex.Reader reader : readers) {
            views.forEachShowing(reader.view(), record ->
                    record.stack().filter(stack -> stack == holder).ifPresent(ignored -> mark(record)));
        }
    }

    private void player(ReadsIndex.Reader[] readers, UUID player) {
        if (player == null) {
            return;
        }
        for (ReadsIndex.Reader reader : readers) {
            String kind = reader.view().kind();
            if (kinds.predicate(kind)) {
                mark(views.candidateOrNull(kind, player));
            } else {
                ViewerRecord record = views.current(player, kind).orElse(null);
                if (record != null && record.definition().equals(reader.view().definition())) {
                    mark(record);
                }
            }
        }
    }

    private void server(ReadsIndex.Reader[] readers) {
        for (ReadsIndex.Reader reader : readers) {
            String kind = reader.view().kind();
            if (!kinds.predicate(kind)) {
                views.forEachShowing(reader.view(), this::mark);
            } else if (reader.role() == ReadRole.VISIBILITY) {
                views.forEachCandidate(kind, this::mark);
            } else {
                views.forEachShowing(reader.view(), record -> mark(views.candidateOrNull(kind, record.player())));
            }
        }
    }

    private void session(ReadsIndex.Reader[] readers, UUID instance) {
        ViewerRecord record = views.sessions().record(instance);
        if (record == null) {
            return;
        }
        ViewRef shown = record.ref();
        for (ReadsIndex.Reader reader : readers) {
            if (reader.view().equals(shown)) {
                mark(record);
                return;
            }
        }
    }

    private void mark(ViewDirty target) {
        if (target != null && target.markDirty()) {
            try {
                wake.wake(target);
            } catch (RuntimeException failure) {
                target.clearDirty();
                if (REPORTED.compareAndSet(false, true)) {
                    LOG.log(Level.WARNING, "The view wake threw; the viewer is left clean (reported once)", failure);
                }
            }
        }
    }
}
