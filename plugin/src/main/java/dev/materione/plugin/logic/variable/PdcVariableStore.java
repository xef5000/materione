package dev.materione.plugin.logic.variable;

import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The player scope of the variable store (logic.md §3.1): each player's value lives in their own
 * persistent data container under {@code materione:var.<pack>.<key>}, so it travels with the player's
 * data and needs no file of its own.
 *
 * <p>The container is looked up per call through {@code holders}: an online player's container, or
 * empty for an offline one (their data is not reachable from here, so the write is refused rather
 * than lost). A value stored under a type the declaration no longer has reads as absent.
 */
public final class PdcVariableStore implements VariableStore {

    private static final String NAMESPACE = "materione";

    private final Function<UUID, Optional<PersistentDataContainer>> holders;

    /** @param holders the container of an online player, or empty when the player is not online */
    public PdcVariableStore(Function<UUID, Optional<PersistentDataContainer>> holders) {
        this.holders = holders;
    }

    @Override
    public VariableScope scope() {
        return VariableScope.PLAYER;
    }

    @Override
    public Optional<Object> read(VariableDeclaration declaration, UUID player) {
        if (player == null) {
            return Optional.empty();
        }
        Optional<PersistentDataContainer> container = holders.apply(player);
        if (container.isEmpty()) {
            return Optional.empty();
        }
        NamespacedKey key = keyOf(declaration);
        try {
            PersistentDataContainer data = container.get();
            return switch (declaration.type()) {
                case NUMBER -> Optional.<Object>ofNullable(data.get(key, PersistentDataType.DOUBLE));
                case STRING -> Optional.<Object>ofNullable(data.get(key, PersistentDataType.STRING));
                case BOOLEAN -> Optional.<Object>ofNullable(data.get(key, PersistentDataType.BOOLEAN));
            };
        } catch (RuntimeException stored) {
            return Optional.empty(); // a value of another type than the declaration: absent, not a crash
        }
    }

    @Override
    public boolean write(VariableDeclaration declaration, UUID player, Object value) {
        if (player == null) {
            return false;
        }
        Optional<PersistentDataContainer> container = holders.apply(player);
        if (container.isEmpty()) {
            return false;
        }
        try {
            NamespacedKey key = keyOf(declaration);
            if (value == null) {
                container.get().remove(key);
                return true;
            }
            switch (declaration.type()) {
                case NUMBER -> container.get().set(key, PersistentDataType.DOUBLE, ((Number) value).doubleValue());
                case STRING -> container.get().set(key, PersistentDataType.STRING, (String) value);
                case BOOLEAN -> container.get().set(key, PersistentDataType.BOOLEAN, (Boolean) value);
            }
            return true;
        } catch (RuntimeException rejected) {
            return false; // a key Bukkit refuses, or a value of the wrong Java type: nothing is written
        }
    }

    /** The container key of a declaration: {@code materione:var.<pack>.<key>}. */
    static NamespacedKey keyOf(VariableDeclaration declaration) {
        return new NamespacedKey(NAMESPACE, "var." + declaration.id().namespace() + "." + declaration.id().value());
    }
}
