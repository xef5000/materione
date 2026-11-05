package dev.materione.plugin.gui.prompt;

import dev.materione.api.logic.InputMethod;
import dev.materione.api.logic.InputMethodDescription;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * {@code materione:chat} (logic.md §6.6): the player types the answer in chat. The question is a chat line; the next
 * message the player sends is their answer and is not broadcast. The listener only asks {@link #awaits} and hands the
 * message to {@link #offer} on the player's thread.
 */
public final class ChatInput implements InputMethod {

    /** The name an author writes. */
    public static final String ID = "chat";

    private final Map<UUID, Reply> waiting = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public InputMethodDescription describe() {
        return InputMethodDescription.of(new SelfDescription("Chat",
                "The player types the answer in chat; the message is not broadcast", List.of(), "1.0.0"), true);
    }

    @Override
    public void ask(Player player, String prompt, Reply reply) {
        waiting.put(player.getUniqueId(), reply);
        player.sendMessage(Component.text(prompt, NamedTextColor.AQUA));
    }

    @Override
    public void dismiss(Player player) {
        waiting.remove(player.getUniqueId());
    }

    /** Whether the player's next chat message is an answer. Read from the chat thread, before the message is cancelled. */
    public boolean awaits(UUID player) {
        return waiting.containsKey(player);
    }

    /**
     * Delivers a chat message as the answer. Call on the player's thread.
     *
     * @return whether the message was taken as an answer
     */
    public boolean offer(UUID player, String text) {
        Reply reply = waiting.remove(player);
        if (reply == null) {
            return false;
        }
        reply.answer(text);
        return true;
    }
}
