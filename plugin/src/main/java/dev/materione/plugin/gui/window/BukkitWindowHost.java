package dev.materione.plugin.gui.window;

import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.input.GuiInput;
import dev.materione.plugin.gui.input.InputGuard;
import dev.materione.plugin.gui.render.WindowRenderer;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * The {@link WindowHost} over Bukkit inventories (gui.md §5). It builds a {@link GuiMenu}, fills it
 * through the {@link WindowRenderer} and opens it on the viewer's thread.
 *
 * <p>Changing a player's window while their click is being handled is unsafe, so when the
 * {@link InputGuard} says the viewer is mid-click, the open or close runs on their next tick through
 * the scheduling gateway; every other caller changes the window at once. The session is already
 * switched either way, so a click that arrives on the old window in between finds a stale session and
 * is ignored.
 */
public final class BukkitWindowHost implements WindowHost {

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final GuiInput input;
    private final InputGuard guard;

    public BukkitWindowHost(Plugin plugin, SchedulingGateway scheduling, GuiInput input, InputGuard guard) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.input = input;
        this.guard = guard;
    }

    @Override
    public void open(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, Runnable opened,
            Consumer<String> failed) {
        show(viewer, session, gui, false, opened, failed);
    }

    @Override
    public void reopen(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, Runnable opened,
            Consumer<String> failed) {
        show(viewer, session, gui, true, opened, failed);
    }

    private void show(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, boolean carryCursor,
            Runnable opened, Consumer<String> failed) {
        Player player = viewer.player();
        GuiMenu menu = new GuiMenu(session, gui.rows(), gui.title(), input);
        WindowRenderer.fill(menu.getInventory(), gui);
        later(viewer, () -> {
            if (!player.isOnline()) {
                failed.accept("the player is offline");
                return;
            }
            if (!session.isOpen()) {
                failed.accept("the session ended before its window opened");
                return;
            }
            // the cursor item is lifted off the old window first, so closing it does not hand the item
            // back to the inventory, and it is put back on the new window
            ItemStack carried = carryCursor ? player.getItemOnCursor() : null;
            boolean holdsItem = carried != null && !carried.getType().isAir();
            if (holdsItem) {
                player.setItemOnCursor(null);
            }
            session.attach(menu);
            boolean shown;
            try {
                shown = player.openInventory(menu.getInventory()) != null;
            } catch (RuntimeException e) {
                shown = false;
            }
            if (holdsItem) {
                player.setItemOnCursor(carried);
            }
            if (!shown) {
                failed.accept("the window was refused by the server (an InventoryOpenEvent was cancelled)");
                return;
            }
            plugin.getLogger().info("MATERIONE-GUI-OPENED id=" + gui.id() + " player=" + viewer.name()
                    + " rows=" + gui.rows() + " slots=" + gui.slots().size());
            opened.run();
        }, () -> failed.accept("the player left before the window opened"));
    }

    @Override
    public void close(Viewer viewer) {
        Player player = viewer.player();
        later(viewer, () -> {
            if (player.isOnline()) {
                player.closeInventory();
            }
        }, () -> { });
    }

    @Override
    public void rewrite(Viewer viewer, GuiSession session, RuntimeGuis.Live gui) {
        menuOf(viewer, session).ifPresent(menu -> WindowRenderer.fill(menu.getInventory(), gui));
    }

    @Override
    public java.util.Optional<Shape> shape(Viewer viewer, GuiSession session) {
        return menuOf(viewer, session).map(menu -> new Shape(menu.title(), menu.rows()));
    }

    private java.util.Optional<GuiMenu> menuOf(Viewer viewer, GuiSession session) {
        Player player = viewer.player();
        if (player == null || !player.isOnline()) {
            return java.util.Optional.empty();
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        return top.getHolder() instanceof GuiMenu menu && menu.session() == session
                ? java.util.Optional.of(menu)
                : java.util.Optional.empty();
    }

    /** Runs {@code task} now, or on the viewer's next tick when they are mid-click. */
    private void later(Viewer viewer, Runnable task, Runnable retired) {
        if (guard.dispatching(viewer.id())) {
            scheduling.executeEntityLater(plugin, viewer.player(), 1, task, retired);
        } else {
            task.run();
        }
    }
}
