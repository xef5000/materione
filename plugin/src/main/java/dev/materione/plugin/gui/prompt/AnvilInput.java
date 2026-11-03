package dev.materione.plugin.gui.prompt;

import dev.materione.api.logic.InputMethod;
import dev.materione.api.logic.InputMethodDescription;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

/**
 * {@code materione:anvil} (logic.md §6.6): the player types the answer as the name of an item in an anvil. The method
 * opens an anvil with a name tag in its first slot, so the anvil has a rename to offer; the player takes the result
 * out of the anvil's third slot, which is the answer. Closing the anvil without taking it is no answer.
 *
 * <p>The result slot is cancelled: the item is never given. The listener reads the rename text and passes the click
 * here with {@link #result}; a click before anything is typed does nothing.
 */
public final class AnvilInput implements InputMethod {

    /** The name an author writes. */
    public static final String ID = "anvil";

    /** The slot the anvil's result sits in. */
    public static final int RESULT_SLOT = 2;

    private final Map<UUID, Anvil> anvils = new ConcurrentHashMap<>();

    /** One open anvil: where the answer goes and which view is the anvil. */
    private record Anvil(Reply reply, InventoryView view) {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public InputMethodDescription describe() {
        return InputMethodDescription.of(new SelfDescription("Anvil",
                "The player types the answer as an item's name in an anvil", List.of(), "1.0.0"), true);
    }

    @Override
    public void ask(Player player, String prompt, Reply reply) {
        AnvilView view = MenuType.ANVIL.create(player, Component.text("Answer"));
        player.openInventory(view);
        if (!(player.getOpenInventory().getTopInventory() instanceof AnvilInventory)) {
            throw new IllegalStateException("the server refused to open the anvil");
        }
        ItemStack tag = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = tag.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(prompt));
            tag.setItemMeta(meta);
        }
        view.setItem(0, tag);
        anvils.put(player.getUniqueId(), new Anvil(reply, view));
    }

    @Override
    public void dismiss(Player player) {
        Anvil anvil = anvils.remove(player.getUniqueId());
        if (anvil != null && player.isOnline() && player.getOpenInventory().getTopInventory() instanceof AnvilInventory) {
            player.closeInventory();
        }
    }

    /** Whether the player has an anvil open for a question. Read from the event thread. */
    public boolean awaits(UUID player) {
        return anvils.containsKey(player);
    }

    /**
     * The player took the result of {@code view} with this rename text. Call on the player's thread.
     *
     * @return whether the view is the question's anvil
     */
    public boolean result(UUID player, InventoryView view, String text) {
        Anvil anvil = anvils.get(player);
        if (anvil == null || !anvil.view().equals(view)) {
            return false;
        }
        anvil.reply().answer(text);
        return true;
    }

    /** The anvil was closed. Call on the player's thread. */
    public boolean closed(UUID player, InventoryView view) {
        Anvil anvil = anvils.get(player);
        if (anvil == null || !anvil.view().equals(view)) {
            return false;
        }
        anvil.reply().decline("the anvil was closed");
        return true;
    }

    /** The rename text of an anvil view, or null when the view is not an anvil. */
    public static String renameText(InventoryView view) {
        return view instanceof AnvilView anvil ? anvil.getRenameText() : null;
    }
}
