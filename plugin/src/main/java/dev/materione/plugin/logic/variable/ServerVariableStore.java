package dev.materione.plugin.logic.variable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The server scope of the variable store (logic.md §3.1): one value per declared variable, kept in
 * {@code storage/variables.json}. It is runtime state, not content (master §8.3), so it never lives in
 * the pack files.
 *
 * <p>Writes update memory at once and mark the store dirty; the owner flushes it on a debounce and on
 * disable. A flush writes a temporary file beside the target and renames it over the target, so a
 * crash mid-write leaves the previous file whole. A file that cannot be parsed is set aside as
 * {@code variables.json.corrupt} and the store starts empty, so one bad file never blocks the server.
 */
public final class ServerVariableStore implements VariableStore {

    private final Path file;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private boolean dirty;
    private long revision;
    private final Object flushLock = new Object();

    /**
     * Opens the store at {@code file}, reading it when it exists.
     *
     * @param warnings receives a line for each recovery (a corrupt file set aside)
     */
    public ServerVariableStore(Path file, Consumer<String> warnings) {
        this.file = file;
        load(warnings);
    }

    @Override
    public VariableScope scope() {
        return VariableScope.SERVER;
    }

    @Override
    public synchronized Optional<Object> read(VariableDeclaration declaration, UUID player) {
        return Optional.ofNullable(values.get(declaration.id().toString()));
    }

    @Override
    public boolean write(VariableDeclaration declaration, UUID player, Object value) {
        if (value != null && !declaration.type().accepts(value)) {
            return false;
        }
        Object canonical = value == null ? null : declaration.type().coerce(value).orElseThrow();
        synchronized (this) {
            String key = declaration.id().toString();
            if (canonical == null) {
                values.remove(key);
            } else {
                values.put(key, canonical);
            }
            dirty = true;
            revision++;
        }
        return true;
    }

    @Override
    public VariableOps.Outcome mutate(
                VariableDeclaration declaration, UUID player, VariableOp operation, Object operand) {
            var checked = VariableOps.apply(operation, declaration.type(), declaration.defaultValue(), operand);
            if (!checked.ok()) return checked;
            Object safeOperand = switch (operation) {
                case SET -> checked.value();
                case ADD, SUBTRACT, MULTIPLY -> dev.materione.api.logic.variable.VariableType.NUMBER
                        .coerce(operand).orElseThrow();
                default -> null;
            };
            synchronized (this) {
                String key = declaration.id().toString();
                Object current = values.getOrDefault(key, declaration.defaultValue());
                var result = VariableOps.apply(operation, declaration.type(), current, safeOperand);
                if (result.ok()) {
                    if (result.value() == null) {
                        values.remove(key);
                    } else {
                        values.put(key, result.value());
                    }
                    dirty = true;
                    revision++;
                }
                return result;
            }
    }

    /** True when a write has not reached the file yet. */
    public synchronized boolean isDirty() {
        return dirty;
    }

    /**
     * Writes the file when anything changed since the last flush. Atomic: the new content goes to a
     * temporary sibling first and is moved over the file in one step.
     *
     * @throws IOException when the file cannot be written; the store stays dirty and a later flush retries
     */
    public void flush() throws IOException {
        synchronized (flushLock) {
            Map<String, Object> snapshot;
            long savedRevision;
            synchronized (this) {
                if (!dirty) return;
                snapshot = new LinkedHashMap<>(values);
                savedRevision = revision;
            }
            var json = new JsonObject();
            for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                json.add(entry.getKey(), toJson(entry.getValue()));
            }
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            synchronized (this) {
                if (revision == savedRevision) dirty = false;
            }
        }
    }

    private void load(Consumer<String> warnings) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                throw new IllegalStateException("the root is not an object");
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                fromJson(entry.getValue()).ifPresent(value -> values.put(entry.getKey(), value));
            }
        } catch (IOException | RuntimeException unreadable) {
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt");
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
                warnings.accept("variables file unreadable (" + unreadable.getMessage() + "); moved to " + aside);
            } catch (IOException moveFailed) {
                warnings.accept("variables file unreadable (" + unreadable.getMessage() + ") and could not be moved");
            }
            values.clear();
        }
    }

    private static JsonElement toJson(Object value) {
        if (value instanceof Number number) {
            return new JsonPrimitive(number.doubleValue());
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    private static Optional<Object> fromJson(JsonElement element) {
        if (!element.isJsonPrimitive()) {
            return Optional.empty();
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return Optional.of(primitive.getAsDouble());
        }
        if (primitive.isBoolean()) {
            return Optional.of(primitive.getAsBoolean());
        }
        return Optional.of(primitive.getAsString());
    }
}
