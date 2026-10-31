package dev.materione.api.logic.variable;

import java.util.Optional;
import java.util.UUID;

/**
 * The storage port for one scope of persistent variables (logic.md §3.1). The default adapters are
 * the player's persistent data container and the server's variable file; a cross-server adapter
 * (roadmap #25, master §8.3) implements the same port for its scope and plugs in without touching the
 * engine.
 *
 * <p>Implementations hold values, never defaults: an unset value reads as empty, and the caller
 * supplies the declaration's default. Values are already of the declared type.
 */
public interface VariableStore {

    /** The scope this store holds. */
    VariableScope scope();

    /**
     * The stored value, or empty when none is stored or the holder is unavailable.
     *
     * @param player the holder for {@link VariableScope#PLAYER}; ignored for {@link VariableScope#SERVER}
     */
    Optional<Object> read(VariableDeclaration declaration, UUID player);

    /**
     * Stores {@code value}, or clears the stored value when it is null.
     *
     * @return false when the holder is unavailable (an offline player) or the write failed
     */
    boolean write(VariableDeclaration declaration, UUID player, Object value);
}
