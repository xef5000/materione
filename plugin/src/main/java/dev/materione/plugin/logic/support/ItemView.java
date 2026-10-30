package dev.materione.plugin.logic.support;

import dev.materione.api.NamespacedId;
import java.util.Optional;

/**
 * The part of one inventory stack that item matching reads, with no Bukkit type in it, so the
 * matching and the take plan are unit-tested without a server. {@link Stacks} fills it from a real
 * stack.
 *
 * @param id the Materione identity, when the stack carries one
 * @param material the vanilla material name ({@code DIAMOND})
 * @param name the plain display name, when the stack has a custom one
 * @param amount the stack size
 */
public record ItemView(Optional<NamespacedId> id, String material, Optional<String> name, int amount) {

    public ItemView {
        id = id == null ? Optional.empty() : id;
        name = name == null ? Optional.empty() : name;
    }
}
