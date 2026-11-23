package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableOps;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.core.runtime.RuntimeVariables;
import dev.materione.plugin.logic.variable.VariableService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A variable service for a test with no server: in-memory player and server stores, declared session variables, and
 * the view registry's {@code session} store behind the same port (views.md section 3.4). A test declares a session
 * variable by key and reads or writes it through the port, as a rule does.
 */
public final class TestVariables {

    private final Map<NamespacedId, VariableDeclaration> declared = new HashMap<>();
    private final ViewRegistry registry;
    private final VariableService service;

    public TestVariables(ViewRegistry registry) {
        this.registry = registry;
        this.service = new VariableService(memory(VariableScope.PLAYER), memory(VariableScope.SERVER),
                () -> new RuntimeVariables(Map.copyOf(declared)), ignored -> { });
        service.sessionStore(registry.sessions());
    }

    /** The service the runtime under test binds its runs through. */
    public VariableService service() {
        return service;
    }

    /** Declares {@code pack:key} as a session variable typed and defaulted like {@code sample}. */
    public VariableDeclaration declare(String pack, String key, Object sample) {
        return declare(pack, key, VariableScope.SESSION, sample);
    }

    /** Declares {@code pack:key} in {@code scope}, typed and defaulted like {@code sample}. */
    public VariableDeclaration declare(String pack, String key, VariableScope scope, Object sample) {
        VariableType type = sample instanceof Boolean ? VariableType.BOOLEAN
                : sample instanceof Number ? VariableType.NUMBER : VariableType.STRING;
        var declaration = new VariableDeclaration(NamespacedId.of(pack, key), scope, type,
                sample, "");
        declared.put(declaration.id(), declaration);
        return declaration;
    }

    /** Writes a session variable of {@code record} through the port, admitting the record first. */
    public boolean put(ViewerRecord record, VariableDeclaration declaration, Object value) {
        registry.admit(record);
        return service.store(VariableScope.SESSION).write(declaration, record.instance(), value);
    }

    /** The value of a session variable of {@code record}, or its declared default. */
    public Object get(ViewerRecord record, VariableDeclaration declaration) {
        return service.store(VariableScope.SESSION).read(declaration, record.instance())
                .orElse(declaration.defaultValue());
    }

    /** An in-memory store for one scope, keyed by declaration and holder. */
    public static VariableStore memory(VariableScope scope) {
        Map<String, Object> values = new HashMap<>();
        return new VariableStore() {
            @Override
            public VariableScope scope() {
                return scope;
            }

            @Override
            public synchronized Optional<Object> read(VariableDeclaration declaration, UUID holder) {
                return Optional.ofNullable(values.get(key(declaration, holder)));
            }

            @Override
            public synchronized boolean write(VariableDeclaration declaration, UUID holder, Object value) {
                if (value == null) {
                    values.remove(key(declaration, holder));
                } else {
                    values.put(key(declaration, holder), value);
                }
                return true;
            }

            @Override
            public synchronized VariableOps.Outcome mutate(
                    VariableDeclaration declaration, UUID holder, VariableOp operation, Object operand) {
                var result = VariableOps.apply(operation, declaration.type(),
                        values.getOrDefault(key(declaration, holder), declaration.defaultValue()), operand);
                if (result.ok()) {
                    write(declaration, holder, result.value());
                }
                return result;
            }

            private String key(VariableDeclaration declaration, UUID holder) {
                return declaration.id() + "@" + holder;
            }
        };
    }
}
