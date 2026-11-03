package dev.materione.plugin.gui.prompt;

import dev.materione.api.scheduling.SchedulingGateway;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.plugin.Plugin;

import java.util.stream.Collectors;

/**
 * The adapter for the events a prompt listens to (logic.md §6.6, §9 rule 1): chat, a sign editor's result, an
 * anvil's result and close, and a player leaving. Each handler asks the input method whether this player is being
 * asked, cancels the event if it is the answer, and hands the answer on. It holds no policy: whether a message is an
 * answer is the method's question, and what an answer means is the prompt's.
 *
 * <p>Chat is asynchronous, so its answer hops to the player's thread before it reaches the method. The other events
 * already run on the player's thread.
 */
public final class PromptListener implements Listener {

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final PromptService prompts;
    private final ChatInput chat;
    private final SignInput sign;
    private final AnvilInput anvil;

    public PromptListener(Plugin plugin, SchedulingGateway scheduling, PromptService prompts,
            ChatInput chat, SignInput sign, AnvilInput anvil) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.prompts = prompts;
        this.chat = chat;
        this.sign = sign;
        this.anvil = anvil;
    }

    /** A chat message while a question waits is the answer: it is not broadcast. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!chat.awaits(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        scheduling.executeEntity(plugin, player, () -> chat.offer(player.getUniqueId(), text));
    }

    /** The sign editor of a question closed: its lines are the answer, and the sign itself is never stored. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (!sign.awaits(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        var plain = PlainTextComponentSerializer.plainText();
        String text = event.lines().stream().map(plain::serialize).filter(line -> !line.isBlank())
                .collect(Collectors.joining(" "));
        sign.edited(player.getUniqueId(), event.getBlock().getLocation(), text);
    }

    /** A click on the anvil's result, once something is typed, is the answer: the item is not given. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAnvilResult(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() != AnvilInput.RESULT_SLOT) {
            return;
        }
        if (!(event.getView().getTopInventory() instanceof AnvilInventory) || !anvil.awaits(player.getUniqueId())) {
            return;
        }
        String text = AnvilInput.renameText(event.getView());
        if (text == null || text.isBlank()) {
            return;
        }
        event.setCancelled(true);
        anvil.result(player.getUniqueId(), event.getView(), text.trim());
    }

    /** The anvil closed without its result taken: no answer. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnvilClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !anvil.awaits(player.getUniqueId())) {
            return;
        }
        anvil.closed(player.getUniqueId(), event.getView());
    }

    /** The player left: their question ends unanswered. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        prompts.playerLeft(event.getPlayer().getUniqueId());
    }
}
