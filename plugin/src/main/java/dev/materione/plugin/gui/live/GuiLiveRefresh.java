package dev.materione.plugin.gui.live;

import dev.materione.api.NamespacedId;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.GuiRefreshPlan;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.open.GuiLifecycle;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.session.GuiSessions;
import dev.materione.plugin.gui.tick.GuiTicker;
import dev.materione.plugin.gui.window.WindowHost;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The live loop for open GUIs (gui.md §5), now session-aware: after every successful swap the viewers
 * of a changed or removed GUI get the new version, and their <em>session carries on</em>. Arguments,
 * session variables and the back stack belong to the session, which a reload does not touch; only the
 * window is rewritten, re-opened or (for a GUI that is gone) closed. The same in-place rewrite is
 * what {@code gui.refresh} does on demand.
 *
 * <p>Runs on the global thread after the swap ({@link #afterSwap}) and touches a viewer only on that
 * viewer's own thread. It decides nothing about content: {@link GuiRefreshPlan} says what each window
 * needs.
 */
public final class GuiLiveRefresh {

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final GuiLookup guis;
    private final GuiSessions sessions;
    private final WindowHost windows;
    private final GuiLifecycle lifecycle;
    private final GuiTicker ticker;

    public GuiLiveRefresh(Plugin plugin, SchedulingGateway scheduling, GuiLookup guis, GuiSessions sessions,
            WindowHost windows, GuiLifecycle lifecycle, GuiTicker ticker) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.guis = guis;
        this.sessions = sessions;
        this.windows = windows;
        this.lifecycle = lifecycle;
        this.ticker = ticker;
    }

    /**
     * Called on the global thread after a successful swap: the GUIs this swap changed or removed are
     * pushed to their viewers; every other window is left alone (a GUI that merely gained tick rules
     * starts ticking for the sessions already open).
     */
    public void afterSwap(RuntimeRegistries previous, RuntimeRegistries current) {
        RuntimeGuis before = viewOf(previous);
        RuntimeGuis after = viewOf(current);
        Map<NamespacedId, GuiRefreshPlan.Change> changes = GuiRefreshPlan.changes(before, after);
        if (!changes.isEmpty()) {
            int removed = (int) changes.values().stream()
                    .filter(change -> change == GuiRefreshPlan.Change.REMOVED).count();
            plugin.getLogger().info("MATERIONE-GUI-REFRESH changed=" + (changes.size() - removed)
                    + " removed=" + removed + " players=" + Bukkit.getOnlinePlayers().size());
        }
        Set<NamespacedId> affected = Set.copyOf(changes.keySet());
        for (GuiSession session : List.copyOf(sessions.all())) {
            Optional<RuntimeGuis.Live> now = after.gui(session.gui());
            boolean refresh = affected.contains(session.gui());
            if (!refresh && now.filter(live -> live.logic().ticks()).isEmpty()) {
                continue;
            }
            Player player = Bukkit.getPlayer(session.player());
            if (player == null) {
                continue;
            }
            scheduling.executeEntity(plugin, player, () -> {
                Viewer viewer = Viewer.of(player);
                if (refresh) {
                    refreshViewer(viewer, session, now);
                } else {
                    ticker.ensure(viewer, session);
                }
            });
        }
    }

    /**
     * {@code gui.refresh}: rewrites the viewer's window from the GUI as it is live now, in place.
     *
     * @return false when the GUI is no longer live or the viewer's window is not this session's
     */
    public boolean refresh(Viewer viewer, GuiSession session) {
        Optional<RuntimeGuis.Live> now = guis.find(session.gui());
        if (now.isEmpty() || windows.shape(viewer, session).isEmpty()) {
            return false;
        }
        refreshViewer(viewer, session, now);
        return true;
    }

    /** One viewer's window brought up to date. Runs on the viewer's own thread. */
    void refreshViewer(Viewer viewer, GuiSession session, Optional<RuntimeGuis.Live> now) {
        Player player = viewer.player();
        if (player == null || !player.isOnline() || !session.isOpen()) {
            return;
        }
        Optional<WindowHost.Shape> shape = windows.shape(viewer, session);
        if (shape.isEmpty()) {
            return;
        }
        switch (GuiRefreshPlan.viewerAction(shape.get().title(), shape.get().rows(), now)) {
            case CLOSE -> {
                lifecycle.removed(viewer, session);
                windows.close(viewer);
                player.sendMessage(Component.text(
                        "The GUI " + session.gui() + " is no longer available; the window was closed.",
                        NamedTextColor.YELLOW));
                plugin.getLogger().info("MATERIONE-GUI-REFRESH-CLOSED id=" + session.gui()
                        + " player=" + viewer.name());
            }
            case REOPEN -> windows.reopen(viewer, session, now.orElseThrow(), () -> ticker.ensure(viewer, session),
                    reason -> lifecycle.removed(viewer, session));
            case UPDATE_CONTENTS -> {
                windows.rewrite(viewer, session, now.orElseThrow());
                ticker.ensure(viewer, session);
            }
        }
    }

    private static RuntimeGuis viewOf(RuntimeRegistries registries) {
        RuntimeGuis view = registries == null ? null : registries.view(RuntimeGuis.KEY);
        return view == null ? RuntimeGuis.empty() : view;
    }
}
