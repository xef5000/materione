package dev.materione.api.logic;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.RegistryEntry;
import java.util.List;
import java.util.Optional;
import org.bukkit.plugin.Plugin;

/**
 * One logic registry (actions, conditions, triggers or functions) as an addon and the engine see
 * it: register a type, look types up, list them. It is a facade over the same
 * {@link dev.materione.api.registry.Registry} that {@code MaterioneAPI.registries()} exposes for
 * introspection (D5); built-ins register through it exactly as an addon does.
 *
 * <h2>Registration</h2>
 *
 * <ul>
 *   <li>Register on the global/main thread during plugin enable, or through the scheduling
 *       gateway. A registration opens one bounded write on this registry only; it edits no
 *       content and reloads nothing. Content that uses the type picks it up on the next normal
 *       pipeline reload, so an addon requests a reload after registering (as for mechanics).
 *   <li>A duplicate id or the reserved {@code minecraft} namespace fails explicitly with a
 *       {@link dev.materione.api.registry.RegistryException}.
 *   <li>{@code id.value()} must equal the type's own {@code id()} ({@link IllegalArgumentException}
 *       otherwise), so the registry key and the name an author writes cannot drift apart.
 *   <li>The type's description and, for actions and conditions, its metadata are read once at
 *       registration; a type whose {@code describe()} throws is rejected and not registered.
 *   <li>Closing the returned handle, or disabling {@code owner}, removes the type again.
 * </ul>
 *
 * <h2>Authored ids</h2>
 *
 * <p>Authors may drop the {@code materione:} namespace on Materione's own types
 * ({@code run_command}); addon types are written qualified ({@code example:greet}). {@link #resolve}
 * applies exactly that rule, so an addon can never shadow a built-in by accident.
 *
 * @param <T> {@code ActionType<?>}, {@code ConditionType<?>}, {@code TriggerType} or
 *        {@code ExpressionFunction}
 */
public interface LogicRegistry<T> {

    /**
     * Registers {@code type} under {@code id}, owned by {@code owner}.
     *
     * @throws dev.materione.api.registry.RegistryException on a duplicate or reserved id
     * @throws IllegalArgumentException when {@code id.value()} differs from the type's own id
     * @throws IllegalStateException when {@code owner} is disabled or the logic service is closed
     */
    LogicRegistration register(Plugin owner, NamespacedId id, T type);

    /** The type registered under exactly {@code id}. */
    Optional<T> get(NamespacedId id);

    /**
     * The type an author's spelling names: {@code take_money} is {@code materione:take_money},
     * {@code example:greet} is itself. Empty for an unknown or malformed spelling.
     */
    Optional<T> resolve(String authoredId);

    /** Every registered entry in registration order, with its description and owner. */
    List<RegistryEntry<T>> entries();
}
