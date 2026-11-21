package dev.materione.plugin.gui.open;

import dev.materione.api.NamespacedId;
import java.util.Map;
import java.util.Optional;
import org.bukkit.command.CommandSender;

/**
 * What runs a {@code command} document when one of its names is typed (logic.md §40). The open path owns the command
 * tree, the arguments and the thread hop; this seam owns the rest: the gate (permission, argument types, requirement,
 * deny), the context, and the flow. It is a seam so the handler is tested without an engine, and so the tree code does
 * not depend on the flow runtime.
 */
public interface DocumentCommands {

    /** No command documents: for a server with none, and for a test of the open path alone. */
    DocumentCommands NONE = new DocumentCommands() {
        @Override
        public Optional<OpenPlan> plan(NamespacedId command) {
            return Optional.empty();
        }

        @Override
        public void run(NamespacedId command, String alias, CommandSender sender, Map<String, String> raw) {
            // nothing declares a command
        }
    };

    /** The live plan of a command document, for the tree's executor and its suggestions; empty when it is not live. */
    Optional<OpenPlan> plan(NamespacedId command);

    /**
     * Runs the command for {@code sender}: the gate in order (permission, arguments, requirement), then the flow.
     * Called on the thread that owns the sender (the player's region; the global thread for the console), so a
     * refusal costs nothing and every world access goes through the gateway from there.
     *
     * @param alias the name the sender typed
     * @param raw the arguments as the tree sent them, by name, before typing
     */
    void run(NamespacedId command, String alias, CommandSender sender, Map<String, String> raw);
}
