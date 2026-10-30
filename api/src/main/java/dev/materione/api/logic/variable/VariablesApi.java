package dev.materione.api.logic.variable;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Declared persistent variables, read and written from Java (logic.md §3.3, §8; reached as
 * {@code api.logic().variables()}). The same declarations and stores the flows use, so an addon's
 * value and a rule's value are one value.
 *
 * <p>Reads are free. A write checks the actor's {@code materione.var} node first, like every other
 * runtime operation (MTR-0055), and fails with a diagnostic rather than throwing.
 */
public interface VariablesApi {

    /** Every declaration the live content defines, in pack order. */
    Collection<VariableDeclaration> declarations();

    /** The declaration of {@code id}, or empty when no live pack declares it. */
    Optional<VariableDeclaration> declaration(NamespacedId id);

    /**
     * The effective value of {@code id} for {@code player}: the stored value, or the declared default
     * when nothing is stored. Empty only when the variable is undeclared. A player-scope variable read
     * for an offline player (or for null) reads as the default, because that player's stored value is
     * not reachable from the server until they are online.
     *
     * @param player the holder for player scope; ignored for server scope (pass null)
     */
    Optional<Object> get(NamespacedId id, UUID player);

    /**
     * Stores {@code value} for {@code player}; {@code null} clears it, so the next read returns the
     * default.
     *
     * @return the stored value on success; a failure carries MTR-0055 (no {@code materione.var}),
     *         MTR-0061 (undeclared), MTR-0062 (the value does not fit the type) or MTR-0063 (no holder,
     *         or the store could not be written)
     */
    ApiResult<Object> set(ActorContext actor, NamespacedId id, UUID player, Object value);
}
