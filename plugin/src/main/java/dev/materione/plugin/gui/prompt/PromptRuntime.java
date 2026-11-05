package dev.materione.plugin.gui.prompt;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.InputMethod;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.gui.GuiBridge;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * The prompt subsystem assembled (logic.md §9): the registry the {@code prompt} action asks through, the three built-in
 * input methods, and the listener that feeds them. The plugin builds one, registers the methods and the action with
 * {@code BuiltinLogic}, and registers {@link #listener} with the server.
 *
 * @param service the prompt registry
 * @param chat the chat method
 * @param sign the sign method
 * @param anvil the anvil method
 */
public record PromptRuntime(PromptService service, ChatInput chat, SignInput sign, AnvilInput anvil) {

    /**
     * Builds the subsystem.
     *
     * @param bridge where the GUI the prompt hides is found; the runtime binds it when it is built
     * @param diagnostics where run-time problems of prompts are published
     */
    public static PromptRuntime create(
            Plugin plugin, SchedulingGateway scheduling, GuiBridge bridge, Consumer<Diagnostic> diagnostics) {
        return new PromptRuntime(new PromptService(plugin, scheduling, bridge, diagnostics),
                new ChatInput(), new SignInput(), new AnvilInput());
    }

    /** The built-in methods, in the order the editor lists them. */
    public List<InputMethod> methods() {
        return List.of(chat, sign, anvil);
    }

    /** The adapter for the events the methods listen to. Register it once with the server. */
    public Listener listener(Plugin plugin, SchedulingGateway scheduling) {
        return new PromptListener(plugin, scheduling, service, chat, anvil);
    }
}
