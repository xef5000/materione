package dev.materione.plugin.gui.input;

import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.session.GuiSessions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The Bukkit side of the crash journal (logic.md §25, decision 12): stores {@link InputJournal} in the player's
 * persistent data and reads the player's current window. The journal always reflects the player's current
 * session's window, so every caller passes only the player: a window that closed while another one is current
 * never touches the journal of the current one.
 */
public final class GuiJournal {

    /** The persistent-data key the journal lives under (namespaced to the plugin). */
    static final String KEY = "gui_inputs";

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final GuiSessions sessions;
    private final InputJournal<Player, ItemStack> journal;

    public GuiJournal(Plugin plugin, SchedulingGateway scheduling, GuiSessions sessions) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.sessions = sessions;
        this.journal = new InputJournal<>(new PdcStore(new NamespacedKey(plugin, KEY)), new ItemCodec());
    }

    /**
     * Makes the journal hold what the player's current window holds in its input slots. Call it after every
     * change to those slots, once the change is made.
     */
    public void mirror(Player player) {
        try {
            journal.mirror(player, held(player));
        } catch (RuntimeException failed) {
            plugin.getLogger().warning("MATERIONE-GUI-JOURNAL could not record the input slots of "
                    + player.getName() + ": " + failed);
        }
    }

    /**
     * As {@link #mirror}, one tick later: for a click the server applies after the handler has returned, so
     * the window does not hold the change yet.
     */
    public void mirrorNextTick(Player player) {
        scheduling.executeEntityLater(plugin, player, 1, () -> mirror(player), () -> { });
    }

    /** Gives back what a crash left in the journal, once; called when the player joins. */
    public void restore(Player player) {
        try {
            for (ItemStack item : journal.restore(player)) {
                InputReturns.giveBack(player, item);
            }
        } catch (RuntimeException failed) {
            plugin.getLogger().warning("MATERIONE-GUI-JOURNAL could not restore the input slots of "
                    + player.getName() + ": " + failed);
        }
    }

    /** The items in the input slots of the player's current, open window (none when no window is current). */
    private List<ItemStack> held(Player player) {
        Optional<GuiSession> current = sessions.current(player.getUniqueId());
        if (current.isEmpty() || !current.get().isOpen() || !(current.get().window() instanceof GuiMenu menu)) {
            return List.of();
        }
        Inventory top = menu.getInventory();
        var out = new ArrayList<ItemStack>();
        for (int slot : menu.inputSlots()) {
            ItemStack item = top.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                out.add(item.clone());
            }
        }
        return out;
    }

    /** The journal's bytes in the player's persistent data. */
    private record PdcStore(NamespacedKey key) implements InputJournal.Store<Player> {

        @Override
        public Optional<byte[]> read(Player holder) {
            return Optional.ofNullable(holder.getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY));
        }

        @Override
        public void write(Player holder, byte[] data) {
            holder.getPersistentDataContainer().set(key, PersistentDataType.BYTE_ARRAY, data);
        }

        @Override
        public void clear(Player holder) {
            holder.getPersistentDataContainer().remove(key);
        }
    }

    /** Stacks as Bukkit's own serialization writes them, so every item comes back exactly as it went in. */
    private static final class ItemCodec implements InputJournal.Codec<ItemStack> {

        @Override
        public byte[] encode(List<ItemStack> items) {
            return ItemStack.serializeItemsAsBytes(items.toArray(new ItemStack[0]));
        }

        @Override
        public List<ItemStack> decode(byte[] data) {
            var out = new ArrayList<ItemStack>();
            for (ItemStack item : ItemStack.deserializeItemsFromBytes(data)) {
                if (item != null && !item.getType().isAir()) {
                    out.add(item);
                }
            }
            return out;
        }
    }
}
