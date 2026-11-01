package dev.materione.plugin.logic.variable;

import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.api.logic.ReadUnavailableException;
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
    private final Function<UUID, Access> access;
    private final boolean strictReads;

    /** Whether the holder is absent or the current thread owns it. */
    public enum Access {
        OFFLINE, OWNED, UNOWNED
    }

    /** @param holders the container of an online player, or empty when the player is not online */
    public PdcVariableStore(Function<UUID, Optional<PersistentDataContainer>> holders) {
        this(holders, ignored -> Access.OWNED, false);
    }

    /** The access probe is checked before looking up or touching a player's container. */
    public PdcVariableStore(
            Function<UUID, Optional<PersistentDataContainer>> holders, Function<UUID, Access> access) {
        this(holders, access, true);
    }

    public PdcVariableStore(
            Function<UUID, Optional<PersistentDataContainer>> holders, Function<UUID, Access> access,
            boolean strictReads) {
        this.holders = holders;
        this.access = access;
        this.strictReads = strictReads;
    }

    @Override
    public VariableScope scope() {
        return VariableScope.PLAYER;
    }

    @Override
    public Optional<Object> read(VariableDeclaration declaration, UUID player) {
        return readPlayer(declaration, player).value();
    }

    record PlayerRead(Optional<Object> value, boolean online) {}

    PlayerRead readPlayer(VariableDeclaration declaration, UUID player) {
        if (player == null) {
            return new PlayerRead(Optional.empty(), false);
        }
        Access state = access.apply(player);
        if (state == Access.OFFLINE) {
            return new PlayerRead(Optional.empty(), false);
        }
        if (state != Access.OWNED) {
            throw new ReadUnavailableException("player variable read requires the player's region");
        }
        Optional<PersistentDataContainer> container = holders.apply(player);
        if (container.isEmpty()) {
            return new PlayerRead(Optional.empty(), false);
        }
        NamespacedKey key = keyOf(declaration);
        try {
            PersistentDataContainer data = container.get();
            Optional<Object> value = switch (declaration.type()) {
                case NUMBER -> Optional.<Object>ofNullable(data.get(key, PersistentDataType.DOUBLE));
                case STRING -> Optional.<Object>ofNullable(data.get(key, PersistentDataType.STRING));
                case BOOLEAN -> Optional.<Object>ofNullable(data.get(key, PersistentDataType.BOOLEAN));
            };
            return new PlayerRead(value, true);
        } catch (RuntimeException stored) {
            if (strictReads) {
                throw new ReadUnavailableException("player variable store could not be read: " + stored.getMessage());
            }
            return new PlayerRead(Optional.empty(), true); // a value of another type is absent
        }
    }

    @Override
    public boolean write(VariableDeclaration declaration, UUID player, Object value) {
        if (player == null) {
            return false;
        }
        if (access.apply(player) != Access.OWNED) {
            return false;
        }
        try {
            Optional<PersistentDataContainer> container = holders.apply(player);
            if (container.isEmpty()) {
                return false;
            }
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
