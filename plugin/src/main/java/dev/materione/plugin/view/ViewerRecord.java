package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ContextFacet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One player attached to one view definition under one binding (views.md §2, §3.3): the single record the view
 * runtime keeps per viewer, for every kind. It replaces the per-kind session type ({@code ViewerRecord}): a fourth
 * kind arrives as a fourth {@link ViewerPayload}, not a fourth session class.
 *
 * <p>What it holds, by the three classes of views.md §3.3:
 *
 * <ul>
 *   <li><b>Binding</b>, fixed at open: the kind, the definition and the arguments (typed for rules as
 *       {@code view.args.*}). Later the subject stack and the key (rows V3, V5).
 *   <li><b>Viewer-local state</b>: the {@link #payload() payload}, the window showing the record and whether a prompt
 *       holds it. Never read by a rule outside the viewer's own runs.
 *   <li><b>Session values</b> ({@code vars.session.*}): per view instance, kept by {@code view.back}. They live in the
 *       {@link SessionVariableStore} behind the variable store port, keyed by {@link #instance()}; the record holds none.
 * </ul>
 *
 * <p>It lives exactly as long as the window is open. A live reload of the definition keeps the same record (the
 * window may be rewritten or re-opened; the record is the player's conversation with the view), while
 * {@code view.back} restores a previous one from a {@link Snapshot}.
 *
 * <p>The record is the cancellation key of every run it starts ({@code BehaviorEvent.withCancelKey}), compared by
 * identity. Runs belong to the player, not to the window (logic.md §5.2), so closing does not cancel them.
 *
 * <p>Threading (views.md §5.6, D8): a record is created, read and mutated on its player's owner thread (the region
 * thread on Folia). The maps are concurrent so a read from the quit or live-refresh path can never corrupt them,
 * and every mutation of viewer-local state reports itself through {@link #mutating(String)}, which a
 * {@link MutationGuard} turns into a check that it happened on the owner thread. The write observer
 * touches only the dirty bit ({@link ViewDirty}), never this state.
 */
public final class ViewerRecord implements ViewDirty {

    /** The facet that carries the record into the runs it starts. Declared here, next to the type it holds. */
    public static final ContextFacet<ViewerRecord> FACET = ContextFacet.of("materione:viewer", ViewerRecord.class);

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final long serial = SEQUENCE.incrementAndGet();
    private final UUID player;
    private final String playerName;
    private final String kind;
    private final NamespacedId definition;
    private final Map<String, String> args;
    private volatile Map<String, Object> typedArgs;
    private final UUID instance = UUID.randomUUID();
    private volatile SessionVariableStore sessions;
    private final AtomicReference<CloseReason> ended = new AtomicReference<>();
    private final ViewerPayload payload;
    private volatile boolean prompting;
    private volatile Object window;
    private volatile MutationGuard guard = MutationGuard.NONE;
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

    /**
     * A record about to open.
     *
     * @param kind the view kind's content type name ({@code gui})
     * @param definition the view definition shown
     * @param args the arguments the view was opened with, by name; copied and read-only afterwards
     * @param payload the kind's viewer-local state
     */
    public ViewerRecord(
            UUID player, String playerName, String kind, NamespacedId definition, Map<String, String> args,
            ViewerPayload payload) {
        this.player = Objects.requireNonNull(player, "player");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.args = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(args, "args")));
        this.payload = Objects.requireNonNull(payload, "payload");
        payload.boundTo(this);
    }

    @Override
    public AtomicBoolean dirtyBit() {
        return dirty;
    }

    /** A process-unique number, for logs. */
    public long serial() {
        return serial;
    }

    @Override
    public UUID player() {
        return player;
    }

    public String playerName() {
        return playerName;
    }

    /** The view kind: the content type name of the definition ({@code gui}). */
    @Override
    public String kind() {
        return kind;
    }

    /** The view definition this record shows. */
    public NamespacedId definition() {
        return definition;
    }

    /** This record's kind and definition, as {@code %materione_view_open%} writes them. */
    public ViewRef ref() {
        return new ViewRef(kind, definition);
    }

    /** The arguments the view was opened with, as text (a back snapshot replays them). */
    public Map<String, String> args() {
        return args;
    }

    /**
     * The arguments as rules read them (logic.md section 40, #467): an {@code int} argument is an integer, a
     * {@code number} a double, a {@code boolean} a boolean, everything else the text it is, exactly as a command's
     * {@code args.*}. A view with no {@code open:} block has none typed, and this is its {@link #args()}. Read by
     * rules as {@code view.args.<name>}.
     */
    public Map<String, Object> typedArgs() {
        Map<String, Object> typed = typedArgs;
        return typed != null ? typed : Collections.<String, Object>unmodifiableMap(args);
    }

    /** Sets the typed reading of the arguments, once, when the record is opened (the opener types them). */
    @ViewerMutator
    public void typedArgs(Map<String, Object> typed) {
        mutating("typed arguments");
        this.typedArgs = Collections.unmodifiableMap(new LinkedHashMap<>(typed));
    }

    /**
     * This view instance's identity: the holder of its session variables in the {@code session} store (views.md §3.4).
     * A replaced view is a new record and so a new instance; {@code view.back} opens a new instance too.
     */
    public UUID instance() {
        return instance;
    }

    /** The kind's viewer-local state. Kinds read it through their own accessor ({@code GuiViewerState.of(record)}). */
    public ViewerPayload payload() {
        return payload;
    }

    /**
     * Whether a prompt holds this record (logic.md §6.6): its window was hidden so the player can answer in chat, a
     * sign or an anvil. The record is still open and still the player's; its window is not closed by the player.
     */
    public boolean prompting() {
        return prompting;
    }

    /** Marks the record as held by a prompt, or released from it. */
    @ViewerMutator
    public void prompting(boolean holding) {
        mutating("prompting");
        prompting = holding;
    }

    /** True until the record ends. */
    public boolean isOpen() {
        return ended.get() == null;
    }

    /** Why the record ended, or null while it is open. */
    public CloseReason endedBecause() {
        return ended.get();
    }

    /**
     * Ends the record. Only the first call counts, so a close that races a replace fires its trigger once. This is
     * the one state change that is safe from any thread: it is a compare-and-set, and the thread that wins fires the
     * kind's close trigger.
     *
     * @return true when this call ended it
     */
    public boolean end(CloseReason reason) {
        boolean won = ended.compareAndSet(null, Objects.requireNonNull(reason, "reason"));
        SessionVariableStore store = sessions;
        if (won && store != null) {
            store.close(instance);
        }
        return won;
    }

    /**
     * The window currently showing this record, as the host recorded it ({@code Object}: the record holds no server
     * type). A window that closes while it is no longer this one was replaced by the host (a re-open after a title
     * change), not closed by the player.
     */
    public Object window() {
        return window;
    }

    /** Records the window that is about to show this record. */
    @ViewerMutator
    public void attach(Object window) {
        mutating("window");
        this.window = window;
    }

    /** What {@code view.back} needs to bring this record's view back as it was. */
    public Snapshot snapshot() {
        SessionVariableStore store = sessions;
        return new Snapshot(kind, definition, args, store == null ? Map.of() : store.values(instance), payload.snapshot());
    }

    /** Puts a snapshot's session values and kind state into this (fresh) record. */
    @ViewerMutator
    public void restore(Snapshot snapshot) {
        mutating("restore");
        SessionVariableStore store = sessions;
        if (store != null) {
            store.restore(instance, snapshot.vars());
        }
        payload.restore(snapshot.state());
    }

    /**
     * Installs the guard that checks mutations of viewer-local state. The {@link ViewRegistry} installs its own when a
     * record is admitted; the default checks nothing.
     */
    void guard(MutationGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /** Binds the store that holds this instance's session values, and opens the instance in it. */
    void sessions(SessionVariableStore store) {
        this.sessions = Objects.requireNonNull(store, "store");
        store.open(this);
        if (!isOpen()) {
            store.close(instance);
        }
    }

    /**
     * Reports that viewer-local state is about to change. Every mutator of the record and of its payload calls it
     * first; with a {@link MutationGuard} installed (the ban test installs a recording one) a mutation off the
     * owner thread is caught at the call, not found later as a corrupted window.
     */
    public void mutating(String what) {
        guard.mutating(this, what);
    }

    @Override
    public String toString() {
        return "viewer#" + serial + "[" + kind + " " + definition + " for " + playerName
                + (isOpen() ? "" : ", ended") + "]";
    }

    /**
     * A record at the moment its window was left for another view: the binding it opened with, the session values it
     * had and the kind's navigation state. Immutable.
     *
     * @param kind the view kind
     * @param definition the view definition
     * @param args the arguments it opened with
     * @param vars the session values, by declared variable
     * @param state the kind's {@link ViewerPayload#snapshot()}
     */
    public record Snapshot(
            String kind, NamespacedId definition, Map<String, String> args, Map<NamespacedId, Object> vars,
            Map<String, Object> state) {

        public Snapshot {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(definition, "definition");
            args = Map.copyOf(args);
            vars = Map.copyOf(vars);
            state = Map.copyOf(state);
        }
    }
}
