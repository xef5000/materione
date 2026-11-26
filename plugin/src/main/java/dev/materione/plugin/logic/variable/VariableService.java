package dev.materione.plugin.logic.variable;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.variable.ViewPort;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.api.logic.variable.StackVariableStore;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.api.logic.variable.VariablesApi;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.core.runtime.RuntimeVariables;
import java.io.IOException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Owns the persistent variables of the server (logic.md §3): the scope stores, the live declarations,
 * the per-run bindings the flows use, and the {@link VariablesApi} the API hands out.
 *
 * <p>The declarations are read through a supplier, so a reload that changes them is seen by the next
 * read without rebuilding this service. The server store is flushed by {@link #autoFlush} on a
 * debounce and once more by {@link #close} on disable.
 */
public final class VariableService implements AutoCloseable {

    /** The permission node a write needs (MTR-0055 when missing). */
    public static final String PERMISSION = "materione.var";

    private final Map<VariableScope, VariableStore> stores = new EnumMap<>(VariableScope.class);
    private final SnapshotVariableStore playerSnapshots;
    private final VariableStore serverStorage;
    private volatile VariableWriteObserver writeObserver = VariableWriteObserver.NONE;
    private final Supplier<RuntimeVariables> declarations;
    private final Consumer<Diagnostic> diagnostics;
    private final StackVariableStore stackStore;
    private final HeldStacks held;
    private final StackMissReports stackMisses;
    private final VariablesApi api = new Api();
    private volatile ViewPort views = ViewPort.NONE;
    private volatile boolean closed;

    /**
     * @param playerStorage the player scope's store (the PDC adapter in the server; a fake in tests)
     * @param serverStorage the server scope's store
     * @param declarations the live declarations, read per call
     * @param diagnostics where store failures are reported
     */
    public VariableService(
            VariableStore playerStorage,
            VariableStore serverStorage,
            Supplier<RuntimeVariables> declarations,
            Consumer<Diagnostic> diagnostics) {
        this(playerStorage, serverStorage, null, HeldStacks.NONE, declarations, diagnostics, System::currentTimeMillis);
    }

    /**
     * As above, with the item scope (logic.md §38).
     *
     * @param stackStorage the item scope's store (the stack PDC adapter in the server; a fake in tests), or null for none
     * @param held finds the stack a player holds, for the command and the API (a run is given its stack by its trigger)
     */
    public VariableService(
            VariableStore playerStorage,
            VariableStore serverStorage,
            StackVariableStore stackStorage,
            HeldStacks held,
            Supplier<RuntimeVariables> declarations,
            Consumer<Diagnostic> diagnostics) {
        this(playerStorage, serverStorage, stackStorage, held, declarations, diagnostics, System::currentTimeMillis);
    }

    VariableService(
            VariableStore playerStorage,
            VariableStore serverStorage,
            StackVariableStore stackStorage,
            HeldStacks held,
            Supplier<RuntimeVariables> declarations,
            Consumer<Diagnostic> diagnostics,
            LongSupplier clock) {
        if (playerStorage.scope() != VariableScope.PLAYER || serverStorage.scope() != VariableScope.SERVER) {
            throw new IllegalArgumentException("each store must hold its own scope");
        }
        if (stackStorage != null && stackStorage.scope() != VariableScope.ITEM) {
            throw new IllegalArgumentException("the stack store must hold the item scope");
        }
        playerSnapshots = new SnapshotVariableStore(playerStorage);
        stores.put(VariableScope.PLAYER, observed(playerSnapshots));
        this.serverStorage = serverStorage;
        stores.put(VariableScope.SERVER, observed(serverStorage));
        this.declarations = declarations;
        this.diagnostics = diagnostics;
        this.stackStore = stackStorage == null
                ? null
                : new ObservedStackVariableStore(stackStorage, () -> writeObserver);
        if (this.stackStore != null) {
            stores.put(VariableScope.ITEM, this.stackStore);
        }
        this.held = held == null ? HeldStacks.NONE : held;
        this.stackMisses = new StackMissReports(clock, diagnostics);
    }

    /** The binding a run for {@code player} (null for a run with no player) reads and writes through. */
    public VariableBinding bind(UUID player) {
        return bind(player, null);
    }

    /**
     * The binding a run for {@code player} about {@code stack} reads and writes through. The stack is what the run's
     * trigger declared (logic.md §38); null for a run with no stack, whose item variables read as their default and
     * report it.
     */
    public VariableBinding bind(UUID player, StackRef stack) {
        return bind(player, stack, null);
    }

    /**
     * As {@link #bind(UUID, StackRef)}, for a run in the view instance {@code view}, whose session variables it reaches
     * (views.md section 3.4); a null instance is a run in no view.
     */
    public VariableBinding bind(UUID player, StackRef stack, UUID view) {
        return new RunVariables(this::declaration, stores, player, stackStore, stack, stackMisses::report, view);
    }

    /** Minimum milliseconds between two reports for the same variable of a run that has no stack. */
    static final long MISS_INTERVAL_MS = StackMissReports.INTERVAL_MS;


    /** The live declaration of {@code id}, or empty. */
    public Optional<VariableDeclaration> declaration(NamespacedId id) {
        return declarations.get().find(id);
    }

    /** Every live declaration. */
    public Collection<VariableDeclaration> declarations() {
        return declarations.get().all();
    }

    /** The API surface: {@code api.logic().variables()}. */
    public VariablesApi api() {
        return api;
    }

    /** Registers the view runtime's port for the PlaceholderAPI expansion (#214, views.md section 3.8). */
    public void views(ViewPort port) {
        this.views = port == null ? ViewPort.NONE : port;
    }

    /** The view port in effect ({@link ViewPort#NONE} until the view runtime registers one). */
    public ViewPort views() {
        return views;
    }

    /**
     * Registers the {@code session} scope's store (the view runtime's). Called once at boot, before any run; a
     * decorator that watches the port wraps the store here, as the player scope's snapshot store does.
     */
    public void sessionStore(VariableStore store) {
        if (store.scope() != VariableScope.SESSION) {
            throw new IllegalArgumentException("the session store must hold the session scope");
        }
        stores.put(VariableScope.SESSION, observed(store));
    }

    /**
     * Registers who hears every successful write to a player, server, session or item variable (views.md §5.3).
     * Replaces the previous observer; the stores consult the current one on each write.
     */
    public void writeObserver(VariableWriteObserver observer) {
        this.writeObserver = observer == null ? VariableWriteObserver.NONE : observer;
    }

    private VariableStore observed(VariableStore store) {
        return new ObservedVariableStore(store, () -> writeObserver);
    }

    /** The store of {@code scope}; null when that scope did not register a store. */
    public VariableStore store(VariableScope scope) {
        return stores.get(scope);
    }

    /** Invalidates one player's snapshots on join, quit, or scheduler retirement. */
    public void invalidatePlayer(UUID player) {
        playerSnapshots.invalidate(player);
    }

    /** A shadow swap invalidates snapshots of declarations from the preceding generation. */
    public void invalidateSnapshots() {
        playerSnapshots.invalidateAll();
    }

    /** Refreshes player snapshots on the player's owning thread. */
    public void refreshPlayer(UUID player) {
        invalidatePlayer(player);
        for (VariableDeclaration declaration : declarations()) {
            if (declaration.scope() == VariableScope.PLAYER) {
                try {
                    playerSnapshots.read(declaration, player);
                } catch (ReadUnavailableException unavailable) {
                    invalidatePlayer(player);
                    diagnostics.accept(new Diagnostic(DiagnosticCodes.VARIABLE_STORE_FAILED, Severity.WARN,
                            "player variable snapshots could not be refreshed: " + unavailable.getMessage(),
                            null, null, "retry on the player's owning thread"));
                    return;
                }
            }
        }
    }

    /**
     * Flushes the server store now when it has changes. A failed flush is reported (MTR-0065) and
     * retried on the next tick, so nothing is lost while the disk recovers.
     */
    public void flushIfDirty() {
        if (!(serverStorage instanceof ServerVariableStore server)) {
            return;
        }
        try {
            server.flush();
        } catch (IOException failed) {
            diagnostics.accept(new Diagnostic(DiagnosticCodes.VARIABLE_STORE_FAILED,
                    Severity.WARN,
                    "server variables could not be written: " + failed.getMessage(), null, null,
                    "check that the server's storage folder is writable; the write is retried"));
        }
    }

    /**
     * Starts the debounced flush: every tick, flush what changed, then ask {@code scheduleLater} to run
     * the next tick. The caller provides the delay, so the loop runs on the platform's global scheduler.
     */
    public void autoFlush(Consumer<Runnable> scheduleLater) {
        scheduleLater.accept(new FlushTick(scheduleLater));
    }

    /** Stops the flush loop and writes the server store one last time. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        invalidateSnapshots();
        flushIfDirty();
    }

    /** One step of the flush loop: flush, then schedule the next step. */
    private final class FlushTick implements Runnable {
        private final Consumer<Runnable> scheduleLater;

        FlushTick(Consumer<Runnable> scheduleLater) {
            this.scheduleLater = scheduleLater;
        }

        @Override
        public void run() {
            if (closed) {
                return;
            }
            flushIfDirty();
            scheduleLater.accept(this);
        }
    }

    /** The {@link VariablesApi}: typed reads, and writes that check the actor and the type first. */
    private final class Api implements VariablesApi {

        @Override
        public Collection<VariableDeclaration> declarations() {
            return VariableService.this.declarations();
        }

        @Override
        public Optional<VariableDeclaration> declaration(NamespacedId id) {
            return VariableService.this.declaration(id);
        }

        @Override
        public Optional<Object> get(NamespacedId id, UUID player) {
            ApiResult<Object> result = getResult(id, player);
            return result.ok() ? Optional.ofNullable(result.value()) : Optional.empty();
        }

        @Override
        public ApiResult<Object> getResult(NamespacedId id, UUID player) {
            Optional<VariableDeclaration> found = declaration(id);
            if (found.isEmpty()) {
                return ApiResult.fail(DiagnosticCodes.VARIABLE_UNDECLARED, id + " is not a declared variable");
            }
            VariableDeclaration declared = found.get();
            if (declared.scope() == VariableScope.SESSION) { return sessionRefused(id); }
            if (declared.scope() == VariableScope.ITEM) {
                return getItem(declared, player);
            }
            VariableStore store = stores.get(declared.scope());
            UUID holder = declared.scope() == VariableScope.PLAYER ? player : null;
            try {
                return ApiResult.ok(store.read(declared, holder).orElse(declared.defaultValue()));
            } catch (ReadUnavailableException unavailable) {
                return playerSnapshots.snapshot(declared, holder)
                        .<ApiResult<Object>>map(value -> ApiResult.ok(value.orElse(declared.defaultValue())))
                        .orElseGet(() -> ApiResult.fail(DiagnosticCodes.VARIABLE_STORE_FAILED,
                                id + " is unavailable: " + unavailable.getMessage()));
            }
        }

        /** A session variable has no holder outside an open view: the API names a player, not a view instance. */
        private ApiResult<Object> sessionRefused(NamespacedId id) {
            return ApiResult.fail(DiagnosticCodes.VARIABLE_STORE_FAILED,
                    id + " is a session variable: it lives in one open view and is reached from that view's rules");
        }

        /** An item variable of the stack the player holds in the main hand (logic.md §38); an empty hand reads the default. */
        private ApiResult<Object> getItem(VariableDeclaration declared, UUID player) {
            if (stackStore == null) {
                return ApiResult.fail(DiagnosticCodes.VARIABLE_STORE_FAILED,
                        declared.id() + ": item variables are not available");
            }
            try {
                Optional<StackRef> stack = player == null ? Optional.empty() : held.mainHand(player);
                if (stack.isEmpty()) {
                    return ApiResult.ok(declared.defaultValue());
                }
                return ApiResult.ok(stackStore.read(declared, stack.get()).orElse(declared.defaultValue()));
            } catch (ReadUnavailableException unavailable) {
                return ApiResult.fail(DiagnosticCodes.VARIABLE_STORE_FAILED,
                        declared.id() + " is unavailable: " + unavailable.getMessage());
            }
        }

        private ApiResult<Object> setItem(VariableDeclaration declared, NamespacedId id, UUID player, Object stored) {
            if (stackStore == null) {
                return ApiResult.fail(DiagnosticCodes.VARIABLE_STORE_FAILED, id + ": item variables are not available");
            }
            if (player == null) {
                return ApiResult.fail(DiagnosticCodes.VARIABLE_STORE_FAILED,
                        id + " is an item variable: name the player holding the item");
            }
            Optional<StackRef> stack;
            try {
                stack = held.mainHand(player);
            } catch (ReadUnavailableException unavailable) {
                return ApiResult.fail(DiagnosticCodes.VARIABLE_STORE_FAILED,
                        id + " is unavailable: " + unavailable.getMessage());
            }
            if (stack.isEmpty() || !stackStore.write(declared, stack.get(), stored)) {
                return ApiResult.fail(DiagnosticCodes.VARIABLE_STORE_FAILED,
                        id + " could not be written (the player is offline, holds nothing, or the store refused)");
            }
            return ApiResult.ok(stored == null ? declared.defaultValue() : stored);
        }

        @Override
        public ApiResult<Object> set(ActorContext actor, NamespacedId id, UUID player, Object value) {
            if (!actor.hasPermission(PERMISSION)) {
                return ApiResult.fail(DiagnosticCodes.PERMISSION_DENIED, "no " + PERMISSION + " permission");
            }
            Optional<VariableDeclaration> found = declaration(id);
            if (found.isEmpty()) {
                return ApiResult.fail(DiagnosticCodes.VARIABLE_UNDECLARED, id + " is not a declared variable");
            }
            VariableDeclaration declared = found.get();
            Object stored = null;
            if (value != null) {
                Optional<Object> coerced = declared.type().coerce(value);
                if (coerced.isEmpty()) {
                    return ApiResult.fail(DiagnosticCodes.VARIABLE_TYPE_MISMATCH,
                            "'" + value + "' is not a " + declared.type().id() + " (" + id + ")");
                }
                stored = coerced.get();
            }
            if (declared.scope() == VariableScope.SESSION) { return sessionRefused(id); }
            if (declared.scope() == VariableScope.ITEM) {
                return setItem(declared, id, player, stored);
            }
            if (declared.scope() == VariableScope.PLAYER && player == null) {
                return ApiResult.fail(DiagnosticCodes.VARIABLE_STORE_FAILED, id + " is a player variable: name a player");
            }
            UUID holder = declared.scope() == VariableScope.PLAYER ? player : null;
            if (!stores.get(declared.scope()).write(declared, holder, stored)) {
                return ApiResult.fail(DiagnosticCodes.VARIABLE_STORE_FAILED,
                        id + " could not be written (the player is offline or the store refused)");
            }
            return ApiResult.ok(stored == null ? declared.defaultValue() : stored);
        }
    }
}
