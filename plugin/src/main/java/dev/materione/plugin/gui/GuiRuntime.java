package dev.materione.plugin.gui;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.runtime.RuntimeApi;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.items.MaterioneStacks;
import dev.materione.platform.text.MaterioneText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Opens and refreshes live GUIs on the {@link PluginGui} kit (gui.md §5). It is the
 * {@link RuntimeApi.GuiRuntimeApi} implementation and the live-refresh hook.
 *
 * <p>Opening validates on the caller's thread (permission, online player, GUI live) and then
 * builds and opens the window on the viewer's region thread (D8). Every click inside a GUI is
 * cancelled by {@link GuiListener}; the GUI's own handler does nothing in v1, so a viewer can
 * look and cannot take anything. The viewer's own inventory stays live.
 *
 * <p>Live refresh runs after every successful swap ({@link #refreshAfterSwap}), on the global
 * thread. It only looks at the GUIs the swap changed or removed, and it touches a viewer only on
 * that viewer's region thread. A viewer of a changed GUI keeps their cursor item and their
 * inventory: the window is rewritten in place when only its items changed, and re-opened with
 * the cursor item put back when its title or size changed.
 */
public final class GuiRuntime implements RuntimeApi.GuiRuntimeApi {

    /** The node that opens a GUI for a player (master §9.2 taxonomy). */
    public static final String OPEN_PERMISSION = "materione.gui.open";

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final Supplier<RuntimeRegistries> live;

    /**
     * @param plugin the owning plugin (region scheduling and logging); a server-less host may pass
     *     null, since only the window and refresh paths use it, and those need a server anyway
     * @param scheduling the thread gateway (D8)
     * @param live the current live registries; null or missing {@link RuntimeGuis} means no GUIs
     */
    public GuiRuntime(Plugin plugin, SchedulingGateway scheduling, Supplier<RuntimeRegistries> live) {
        this.plugin = plugin;
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.live = Objects.requireNonNull(live, "live");
    }

    // --- catalog ------------------------------------------------------------

    @Override
    public List<RuntimeApi.GuiView> guis() {
        var out = new ArrayList<RuntimeApi.GuiView>();
        for (RuntimeGuis.Live gui : viewOf(live.get()).all()) {
            out.add(viewOf(gui));
        }
        return out;
    }

    @Override
    public Optional<RuntimeApi.GuiView> gui(NamespacedId id) {
        return viewOf(live.get()).gui(id).map(GuiRuntime::viewOf);
    }

    @Override
    public Optional<String> onlineName(UUID player) {
        Player online = onlinePlayer(player);
        return online == null ? Optional.empty() : Optional.ofNullable(online.getName());
    }

    // --- open -----------------------------------------------------------------

    @Override
    public CompletableFuture<ApiResult<Void>> open(ActorContext actor, NamespacedId guiId, UUID targetPlayer) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(guiId, "guiId");
        CompletableFuture<ApiResult<Void>> future = new CompletableFuture<>();
        if (!actor.hasPermission(OPEN_PERMISSION)) {
            future.complete(ApiResult.fail(DiagnosticCodes.PERMISSION_DENIED,
                    "no permission (" + OPEN_PERMISSION + ")"));
            return future;
        }
        Player player = onlinePlayer(targetPlayer);
        if (player == null) {
            future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE, "player is not online"));
            return future;
        }
        RuntimeGuis.Live gui = viewOf(live.get()).gui(guiId).orElse(null);
        if (gui == null) {
            future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE,
                    "GUI '" + guiId + "' is not live: it is unknown, or its last compile quarantined it"));
            return future;
        }
        try {
            scheduling.executeEntity(plugin, player, () -> {
                try {
                    if (!player.isOnline()) {
                        future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE, "player is not online"));
                        return;
                    }
                    openWindow(player, gui);
                    future.complete(ApiResult.ok(null));
                } catch (RuntimeException e) {
                    future.complete(ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                            "GUI open failed: " + e.getMessage()));
                }
            });
        } catch (RuntimeException e) {
            future.complete(ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "GUI open could not be scheduled: " + e.getMessage()));
        }
        return future;
    }

    /** Builds a fresh window for one GUI and opens it. Runs on the viewer's region thread. */
    private void openWindow(Player player, RuntimeGuis.Live gui) {
        GuiMenu menu = new GuiMenu(gui.id(), gui.rows(), gui.title());
        fill(menu.getInventory(), gui);
        player.openInventory(menu.getInventory());
        plugin.getLogger().info("MATERIONE-GUI-OPENED id=" + gui.id() + " player=" + player.getName()
                + " rows=" + gui.rows() + " slots=" + gui.slots().size());
    }

    // --- live refresh ---------------------------------------------------------

    /**
     * Live refresh (gui.md §5): called on the global thread after a successful swap. The GUIs
     * this swap changed or removed are pushed to their viewers; every other window is left alone.
     *
     * @param previous the registries the swap replaced
     * @param current the registries the swap published
     */
    public void refreshAfterSwap(RuntimeRegistries previous, RuntimeRegistries current) {
        RuntimeGuis before = viewOf(previous);
        RuntimeGuis after = viewOf(current);
        Map<NamespacedId, GuiRefreshPlan.Change> changes = GuiRefreshPlan.changes(before, after);
        if (changes.isEmpty()) {
            return;
        }
        Set<NamespacedId> affected = Set.copyOf(changes.keySet());
        int removed = (int) changes.values().stream()
                .filter(change -> change == GuiRefreshPlan.Change.REMOVED).count();
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        plugin.getLogger().info("MATERIONE-GUI-REFRESH changed=" + (changes.size() - removed)
                + " removed=" + removed + " players=" + players.size());
        for (Player player : players) {
            scheduling.executeEntity(plugin, player, () -> refreshViewer(player, affected, after));
        }
    }

    /** One viewer's window, brought up to date. Runs on the viewer's region thread. */
    private void refreshViewer(Player player, Set<NamespacedId> affected, RuntimeGuis after) {
        if (!player.isOnline()) {
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!(top.getHolder() instanceof GuiMenu menu) || !affected.contains(menu.guiId())) {
            return;
        }
        Optional<RuntimeGuis.Live> now = after.gui(menu.guiId());
        switch (GuiRefreshPlan.viewerAction(menu.title(), menu.rows(), now)) {
            case CLOSE -> {
                player.closeInventory();
                player.sendMessage(Component.text(
                        "The GUI " + menu.guiId() + " is no longer available; the window was closed.",
                        NamedTextColor.YELLOW));
                plugin.getLogger().info("MATERIONE-GUI-REFRESH-CLOSED id=" + menu.guiId()
                        + " player=" + player.getName());
            }
            case REOPEN -> reopen(player, now.orElseThrow());
            case UPDATE_CONTENTS -> fill(top, now.orElseThrow());
        }
    }

    /**
     * Re-opens a viewer's window with a new title or size. The cursor item is lifted off the old
     * window first, so closing it does not hand the item back to the inventory, and it is put
     * back onto the new window. Only the window changes; the player's inventory does not.
     */
    private void reopen(Player player, RuntimeGuis.Live gui) {
        ItemStack carried = player.getItemOnCursor();
        boolean holdsItem = carried != null && !carried.getType().isAir();
        if (holdsItem) {
            player.setItemOnCursor(null);
        }
        GuiMenu menu = new GuiMenu(gui.id(), gui.rows(), gui.title());
        fill(menu.getInventory(), gui);
        player.openInventory(menu.getInventory());
        if (holdsItem) {
            // onto the new window, or back onto whichever window is open if the new one was refused
            player.setItemOnCursor(carried);
        }
    }

    // --- helpers --------------------------------------------------------------

    /** Writes a GUI's slot table into an inventory: the items come from the item factory. */
    static void fill(Inventory inventory, RuntimeGuis.Live gui) {
        inventory.clear();
        for (RuntimeGuis.Slot slot : gui.slots()) {
            inventory.setItem(slot.slot(), stackOf(slot));
        }
    }

    private static ItemStack stackOf(RuntimeGuis.Slot slot) {
        ItemStack stack = switch (slot.item()) {
            case RuntimeGuis.Item.Materione materione ->
                    MaterioneStacks.build(MaterioneStacks.specOf(materione.item()));
            case RuntimeGuis.Item.Vanilla vanilla -> new ItemStack(vanilla.material());
        };
        stack.setAmount(Math.max(1, Math.min(slot.amount(), stack.getMaxStackSize())));
        return stack;
    }

    private static RuntimeGuis viewOf(RuntimeRegistries registries) {
        RuntimeGuis view = registries == null ? null : registries.view(RuntimeGuis.KEY);
        return view == null ? RuntimeGuis.empty() : view;
    }

    private static RuntimeApi.GuiView viewOf(RuntimeGuis.Live gui) {
        return new RuntimeApi.GuiView(gui.id(), gui.rows(), gui.accessibleTitle(), gui.slots().size());
    }

    /** Player lookup that fails soft when no server is up (tests, early bootstrap). */
    private static Player onlinePlayer(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            return Bukkit.getPlayer(uuid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The window a live GUI opens: a {@link PluginGui} that remembers which GUI and shape it
     * shows, so a refresh can tell whether an open window still fits the GUI. Its handler is
     * empty: the router has already cancelled the click, and v1 GUIs have no click bindings
     * (gui.md §8).
     */
    static final class GuiMenu extends PluginGui {

        private final NamespacedId guiId;
        private final int rows;
        private final String title;

        GuiMenu(NamespacedId guiId, int rows, String title) {
            super(rows * 9, MaterioneText.parseData(title));
            this.guiId = guiId;
            this.rows = rows;
            this.title = title;
        }

        NamespacedId guiId() {
            return guiId;
        }

        int rows() {
            return rows;
        }

        String title() {
            return title;
        }

        @Override
        protected void onClick(InventoryClickEvent event) {
            // v1: a GUI has no click bindings; the click was cancelled by GuiListener.
        }
    }

}
