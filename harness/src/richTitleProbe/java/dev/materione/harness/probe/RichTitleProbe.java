package dev.materione.harness.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only server observer, installed solely by the opt-in Paper floor harness. */
public final class RichTitleProbe extends JavaPlugin implements Listener {
    private int opens;
    private int closes;
    private int baselineOpens;
    private int baselineCloses;
    private int nativeId;
    private long clock;
    private Object nativeMenu;
    private Object holder;
    private Object session;
    private Inventory inventory;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void opened(InventoryOpenEvent event) {
        if (event.getPlayer().getName().equals("FloorBot")) opens++;
    }

    @EventHandler
    public void closed(InventoryCloseEvent event) {
        if (event.getPlayer().getName().equals("FloorBot")) closes++;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String stage = args.length == 0 ? "" : args[0];
        try {
            Player player = Bukkit.getPlayerExact("FloorBot");
            require(player != null && player.isOnline(), "player is not online");
            if (stage.equals("start")) {
                start(player);
            } else if (stage.equals("shown") || stage.equals("hidden") || stage.equals("steady")) {
                check(player, stage.equals("shown"), stage.equals("steady"));
            } else {
                throw new IllegalArgumentException("unknown stage");
            }
            getLogger().info("MATERIONE-FLOOR-PROBE stage=" + stage + " ok=true id=" + nativeId
                    + " session=" + call(session, "serial") + " ticks=" + call(session, "windowTicks"));
        } catch (Exception failure) {
            getLogger().severe("MATERIONE-FLOOR-PROBE stage=" + stage + " ok=false error=" + failure);
        }
        return true;
    }

    private void start(Player player) throws Exception {
        inventory = player.getOpenInventory().getTopInventory();
        holder = inventory.getHolder();
        require(holder != null && holder.getClass().getName().equals("dev.materione.plugin.gui.GuiMenu"),
                "production GUI is not open");
        session = call(holder, "session");
        nativeMenu = nativeMenu(player);
        nativeId = id(nativeMenu);
        require(nativeId != 0, "native menu id is zero");
        baselineOpens = opens;
        baselineCloses = closes;
        inventory.setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.setItemOnCursor(new ItemStack(Material.EMERALD, 2));
        player.updateInventory();
        clock = (long) call(session, "windowTicks");
        check(player, false, false);
    }

    private void check(Player player, boolean selected, boolean steady) throws Exception {
        require(inventory != null && player.getOpenInventory().getTopInventory() == inventory, "inventory changed");
        require(inventory.getHolder() == holder && call(holder, "session") == session, "window/session changed");
        require(nativeMenu(player) == nativeMenu && id(nativeMenu) == nativeId, "server native menu/id changed");
        require(opens == baselineOpens && closes == baselineCloses, "Bukkit open/close event fired");
        require((boolean) call(session, "isOpen"), "session ended");
        long now = (long) call(session, "windowTicks");
        require(now >= clock && (!steady || now > clock), "window clock reset or did not advance");
        clock = now;
        require(stack(inventory.getItem(0), Material.DIAMOND, 3), "input stack changed");
        require(stack(player.getItemOnCursor(), Material.EMERALD, 2), "cursor stack changed");
        @SuppressWarnings("unchecked")
        Set<Integer> inputs = (Set<Integer>) call(holder, "inputSlots");
        require(inputs.contains(0), "input ownership lost");
        require(inputs.contains(4) != selected, "overlay/input hitbox disagrees with visibility");
        Object rendered = call(session, "rendered");
        @SuppressWarnings("unchecked")
        Set<String> hidden = (Set<String>) call(rendered, "hidden");
        require(hidden.contains("selected") != selected && hidden.contains("label") != selected,
                "render visibility disagrees with state");
        @SuppressWarnings("unchecked")
        Map<String, Set<Integer>> coverage = (Map<String, Set<Integer>>) call(rendered, "coverage");
        require(!selected || coverage.getOrDefault("selected", Set.of()).contains(4),
                "visible overlay does not cover its authored slot");
        String title = (String) call(rendered, "title");
        require(title != null && title.contains(selected ? "<font:overlay:gui/default_y6>" : "<font:overlay:gui>"),
                "render lost the custom font");
        require(title.contains("ACTIVE") == selected, "title text disagrees with visibility");
    }

    private static boolean stack(ItemStack item, Material material, int amount) {
        return item != null && item.getType() == material && item.getAmount() == amount;
    }

    private static Object nativeMenu(Player player) throws Exception {
        Object handle = call(player, "getHandle");
        Field field = handle.getClass().getField("containerMenu");
        return field.get(handle);
    }

    private static int id(Object menu) throws Exception {
        return menu.getClass().getField("containerId").getInt(menu);
    }

    private static Object call(Object object, String method) throws Exception {
        Method resolved = object.getClass().getMethod(method);
        return resolved.invoke(object);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
}
