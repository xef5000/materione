package dev.materione.harness.probe;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only scaffolding for gui.md section 12, row R (#289): opens a hopper or a dispenser through the
 * legacy path, {@code Bukkit.createInventory(holder, InventoryType, Component)}, with a holder of its own,
 * and reports what the server and the client are told. It is not product code: it implements none of the
 * {@code container:} model, the compiler profiles or the runtime opener (#290, #291, #292), and it never
 * ships. Built by {@code :harness:containerProbeJar}; installed by the harness IT and by hand on the probe.
 *
 * <pre>
 *   ctprobe describe &lt;hopper|dispenser&gt;                  no player needed: type, size, menu type, holder
 *   ctprobe open &lt;hopper|dispenser&gt; &lt;player&gt; [title] [empty]
 *                                                         open it; [title] is a JSON file under the data folder
 *                                                         (else plain text); a slot holds an item unless "empty"
 *   ctprobe title &lt;player&gt; &lt;title&gt;                      refresh the open window's title (the packet the product sends)
 *   ctprobe top &lt;player&gt;                              the open top inventory of a window the product opened (row P, gui.md 12.6)
 * </pre>
 *
 * Every answer is one log line containing {@code MATERIONE-CTPROBE}.
 */
public final class ContainerProbe extends JavaPlugin implements Listener {

    /** The window's identity, as {@code PluginGui} is for the product: a holder routes the click. */
    static final class ProbeHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getDataFolder().mkdirs();
        getLogger().info("MATERIONE-CTPROBE ready");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String stage = args.length == 0 ? "" : args[0];
        try {
            switch (stage) {
                case "describe" -> describe(args[1], inventoryOf(args[1], title(args.length > 2 ? args[2] : null, args[1])));
                case "open" -> open(args);
                case "title" -> retitle(args);
                case "top" -> top(args[1]);
                default -> throw new IllegalArgumentException("unknown stage '" + stage + "'");
            }
        } catch (Exception failure) {
            getLogger().severe("MATERIONE-CTPROBE stage=" + stage + " ok=false error=" + failure);
        }
        return true;
    }

    private void open(String[] args) throws Exception {
        Player player = Bukkit.getPlayerExact(args[2]);
        if (player == null) {
            throw new IllegalStateException("player is not online");
        }
        ProbeHolder holder = new ProbeHolder();
        Inventory inventory = create(holder, args[1], title(args.length > 3 ? args[3] : null, args[1]));
        holder.inventory = inventory;
        if (args.length <= 4 || !args[4].equals("empty")) {
            fill(inventory);
        }
        player.openInventory(inventory);
        describe(args[1], inventory);
        var view = player.getOpenInventory();
        Object menu = field(handle(player), "containerMenu");
        getLogger().info("MATERIONE-CTPROBE open ok=true viewType=" + view.getType()
                + " topSize=" + view.getTopInventory().getSize()
                + " holder=" + (view.getTopInventory().getHolder() == holder)
                + " menuSlots=" + ((List<?>) field(menu, "slots")).size()
                + " menuId=" + field(menu, "containerId"));
    }

    /**
     * What a player's open top inventory is, for a window the product runtime opened (gui.md 12.6): the inventory type
     * and size, the menu the server holds for it and its id (the id the client's open-window packet carries), and
     * whether the holder is a product {@code PluginGui} (checked by class name: this plugin has no product dependency).
     */
    private void top(String name) throws Exception {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            throw new IllegalStateException("player is not online");
        }
        var view = player.getOpenInventory();
        Inventory top = view.getTopInventory();
        Object holder = top.getHolder();
        boolean product = false;
        for (Class<?> type = holder == null ? null : holder.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("dev.materione.plugin.gui.PluginGui")) {
                product = true;
            }
        }
        Object menu = field(handle(player), "containerMenu");
        Object menuType = Class.forName("org.bukkit.craftbukkit.inventory.CraftContainer")
                .getMethod("getNotchInventoryType", Inventory.class).invoke(null, top);
        getLogger().info("MATERIONE-CTPROBE top ok=true player=" + name + " viewType=" + view.getType()
                + " type=" + top.getType() + " size=" + top.getSize()
                + " menuType=" + menuTypeKey(menuType)
                + " menuId=" + field(menu, "containerId")
                + " menuSlots=" + ((List<?>) field(menu, "slots")).size()
                + " holder=" + (holder == null ? "none" : holder.getClass().getSimpleName())
                + " product=" + product);
    }

    private void retitle(String[] args) throws Exception {
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null || !(player.getOpenInventory().getTopInventory().getHolder() instanceof ProbeHolder)) {
            throw new IllegalStateException("no probe window is open");
        }
        Inventory expected = player.getOpenInventory().getTopInventory();
        Component title = title(args[2], "title");
        // The transport the product uses (platform PaperRichTitles.send): a ClientboundOpenScreenPacket
        // carrying the open menu's id, the type the inventory maps to, and the new title, then a resync.
        Object handle = handle(player);
        Object menu = field(handle, "containerMenu");
        int id = (int) field(menu, "containerId");
        Class<?> craftContainer = Class.forName("org.bukkit.craftbukkit.inventory.CraftContainer");
        Object type = craftContainer.getMethod("getNotchInventoryType", Inventory.class).invoke(null, expected);
        Object nativeTitle = Class.forName("io.papermc.paper.adventure.PaperAdventure")
                .getMethod("asVanilla", Component.class).invoke(null, title);
        Class<?> open = Class.forName("net.minecraft.network.protocol.game.ClientboundOpenScreenPacket");
        Class<?> menuType = Class.forName("net.minecraft.world.inventory.MenuType");
        Class<?> nativeComponent = Class.forName("net.minecraft.network.chat.Component");
        Object packet = open.getConstructor(int.class, menuType, nativeComponent).newInstance(id, type, nativeTitle);
        Object connection = field(handle, "connection");
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.Packet");
        connection.getClass().getMethod("send", packetClass).invoke(connection, packet);
        player.updateInventory();
        getLogger().info("MATERIONE-CTPROBE title ok=true id=" + id + " menuType=" + menuTypeKey(type));
    }

    private static Inventory create(ProbeHolder holder, String kind, Component title) {
        return Bukkit.createInventory(holder, typeOf(kind), title);
    }

    private static Inventory inventoryOf(String kind, Component title) {
        return create(new ProbeHolder(), kind, title);
    }

    private static InventoryType typeOf(String kind) {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "hopper" -> InventoryType.HOPPER;
            case "dispenser" -> InventoryType.DISPENSER;
            default -> throw new IllegalArgumentException("unknown container '" + kind + "'");
        };
    }

    /** Type, size, the menu type the client is told, and the holder: all knowable without a player. */
    private void describe(String kind, Inventory inventory) throws Exception {
        Class<?> craftContainer = Class.forName("org.bukkit.craftbukkit.inventory.CraftContainer");
        Object type = craftContainer.getMethod("getNotchInventoryType", Inventory.class).invoke(null, inventory);
        getLogger().info("MATERIONE-CTPROBE describe kind=" + kind + " ok=true type=" + inventory.getType()
                + " size=" + inventory.getSize()
                + " menuType=" + menuTypeKey(type)
                + " holder=" + (inventory.getHolder() instanceof ProbeHolder));
    }

    /** {@code minecraft:hopper}, {@code minecraft:generic_3x3}: the registry key of a menu type. */
    private static String menuTypeKey(Object menuType) throws Exception {
        Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
        Object registry = builtIn.getField("MENU").get(null);
        Method getKey = null;
        for (Method candidate : registry.getClass().getMethods()) {
            if (candidate.getName().equals("getKey") && candidate.getParameterCount() == 1) {
                getKey = candidate;
                break;
            }
        }
        if (getKey == null) {
            throw new NoSuchMethodException("Registry.getKey");
        }
        return String.valueOf(getKey.invoke(registry, menuType));
    }

    private Component title(String name, String fallback) throws IOException {
        if (name == null) {
            return Component.text("Probe " + fallback);
        }
        Path file = getDataFolder().toPath().resolve(name + ".json");
        if (!Files.isRegularFile(file)) {
            return Component.text(name);
        }
        return GsonComponentSerializer.gson().deserialize(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** A different item in every slot, so a click by slot id and a screenshot both identify the slot. */
    private static void fill(Inventory inventory) {
        Material[] items = {Material.APPLE, Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT,
            Material.IRON_INGOT, Material.COAL, Material.REDSTONE, Material.LAPIS_LAZULI, Material.QUARTZ};
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, new ItemStack(items[slot % items.length], slot + 1));
        }
    }

    @EventHandler
    public void clicked(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ProbeHolder)) {
            return;
        }
        event.setCancelled(true);
        // The same three facts GuiListener.route reads: is the click outside, in the top inventory, and which slot
        Inventory clicked = event.getClickedInventory();
        String where = clicked == null ? "outside" : clicked.equals(event.getView().getTopInventory()) ? "top" : "bottom";
        getLogger().info("MATERIONE-CTPROBE click where=" + where + " slot=" + event.getSlot()
                + " raw=" + event.getRawSlot() + " slotType=" + event.getSlotType()
                + " click=" + event.getClick() + " action=" + event.getAction()
                + " topSize=" + event.getView().getTopInventory().getSize());
    }

    @EventHandler
    public void closed(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ProbeHolder) {
            getLogger().info("MATERIONE-CTPROBE close player=" + event.getPlayer().getName());
        }
    }

    @EventHandler
    public void opened(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof ProbeHolder) {
            getLogger().info("MATERIONE-CTPROBE opened player=" + event.getPlayer().getName());
        }
    }

    private static Object handle(Player player) throws Exception {
        return player.getClass().getMethod("getHandle").invoke(player);
    }

    private static Object field(Object target, String name) throws Exception {
        return target.getClass().getField(name).get(target);
    }
}
