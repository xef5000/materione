package dev.materione.plugin.logic.variable;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.variable.GuiSessionPort;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.api.logic.variable.VariablesApi;
import dev.materione.core.runtime.RuntimeVariables;
import java.io.IOException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the persistent variables of the server (logic.md §3): the two stores, the live declarations,
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
    private final Supplier<RuntimeVariables> declarations;
    private final Consumer<Diagnostic> diagnostics;
    private final VariablesApi api = new Api();
    private volatile GuiSessionPort guiSessions = GuiSessionPort.NONE;
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
        if (playerStorage.scope() != VariableScope.PLAYER || serverStorage.scope() != VariableScope.SERVER) {
            throw new IllegalArgumentException("each store must hold its own scope");
        }
        stores.put(VariableScope.PLAYER, playerStorage);
        stores.put(VariableScope.SERVER, serverStorage);
        this.declarations = declarations;
        this.diagnostics = diagnostics;
    }

    /** The binding a run for {@code player} (null for a run with no player) reads and writes through. */
    public VariableBinding bind(UUID player) {
        return new RunVariables(this::declaration, stores, player);
    }

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

    /** Registers the GUI runtime's session port for the PlaceholderAPI expansion (#214). */
    public void guiSessions(GuiSessionPort port) {
        this.guiSessions = port == null ? GuiSessionPort.NONE : port;
    }

    /** The GUI session port in effect ({@link GuiSessionPort#NONE} until the GUI runtime registers one). */
    public GuiSessionPort guiSessions() {
        return guiSessions;
    }

    /** The store of {@code scope}. */
    public VariableStore store(VariableScope scope) {
        return stores.get(scope);
    }

    /**
     * Flushes the server store now when it has changes. A failed flush is reported (MTR-0063) and
     * retried on the next tick, so nothing is lost while the disk recovers.
     */
    public void flushIfDirty() {
        if (!(stores.get(VariableScope.SERVER) instanceof ServerVariableStore server)) {
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
            return declaration(id).map(declared -> {
                VariableStore store = stores.get(declared.scope());
                UUID holder = declared.scope() == VariableScope.PLAYER ? player : null;
                return store.read(declared, holder).orElse(declared.defaultValue());
            });
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
