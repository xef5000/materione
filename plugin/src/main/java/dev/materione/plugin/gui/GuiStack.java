package dev.materione.plugin.gui;

import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.event.RuleDispatcher;
import dev.materione.plugin.gui.input.ClickRouter;
import dev.materione.plugin.gui.input.GuiWindowListener;
import dev.materione.plugin.gui.input.InputGuard;
import dev.materione.plugin.gui.input.InventoryInput;
import dev.materione.plugin.gui.input.LayerVisibility;
import dev.materione.plugin.gui.live.GuiLiveRefresh;
import dev.materione.plugin.gui.open.GuiLifecycle;
import dev.materione.plugin.gui.render.GuiRenderer;
import dev.materione.plugin.gui.render.LoggingRenderReporter;
import dev.materione.plugin.gui.open.GuiOpener;
import dev.materione.plugin.gui.session.GuiSessions;
import dev.materione.plugin.gui.tick.GuiTicker;
import dev.materione.plugin.gui.window.BukkitWindowHost;
import dev.materione.plugin.gui.window.WindowHost;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * The GUI runtime, assembled (logic.md §9): sessions, events, router, window host, opener, lifecycle,
 * ticker and live refresh, wired in one place so that every collaborator takes only what it uses and
 * none of them reaches for a singleton. The plugin builds one, binds its services into the
 * {@link GuiBridge} the actions hold, registers {@link #listeners()} and hands the opener to the API.
 *
 * <p>Nothing here holds policy. The wiring is the whole of this class.
 */
public final class GuiStack {

    private final GuiSessions sessions = new GuiSessions();
    private final GuiOpener opener;
    private final GuiLifecycle lifecycle;
    private final GuiLiveRefresh refresh;
    private final ClickRouter router;
    private final GuiWindowListener windowListener;
    private final GuiBridge.Services services;

    /**
     * @param live the registries that are live now
     * @param dispatcher where fired triggers go: the rule engine
     * @param visibility whether a layer is showing for a session (the router skips a hidden one)
     */
    public GuiStack(Plugin plugin, SchedulingGateway scheduling, Supplier<RuntimeRegistries> live,
            RuleDispatcher dispatcher, LayerVisibility visibility) {
        GuiLookup guis = GuiLookup.over(live);
        InputGuard guard = new InputGuard();
        GuiEvents events = new GuiEvents(dispatcher);
        this.router = new ClickRouter(guis, events, visibility, guard);
        GuiRenderer renderer = new GuiRenderer(new LoggingRenderReporter(plugin.getLogger()));
        WindowHost windows = new BukkitWindowHost(
                plugin, scheduling, new InventoryInput(sessions, router), guard, renderer);
        GuiTicker ticker = new GuiTicker(guis, events,
                (viewer, ticks, task, retired) -> scheduling.executeEntityLater(
                        plugin, viewer.player(), ticks, task, retired),
                windows);
        this.lifecycle = new GuiLifecycle(guis, sessions, events, windows);
        this.opener = new GuiOpener(guis, sessions, events, windows, ticker, plugin.getLogger());
        this.refresh = new GuiLiveRefresh(plugin, scheduling, guis, sessions, windows, lifecycle, ticker);
        this.windowListener = new GuiWindowListener(lifecycle);
        this.services = new GuiBridge.Services(guis, sessions, opener, lifecycle, refresh);
    }

    public GuiSessions sessions() {
        return sessions;
    }

    /** The one open path (logic.md §6.1). */
    public GuiOpener opener() {
        return opener;
    }

    public GuiLiveRefresh refresh() {
        return refresh;
    }

    public ClickRouter router() {
        return router;
    }

    /** What the actions need, to bind into the {@link GuiBridge}. */
    public GuiBridge.Services services() {
        return services;
    }

    /** The listeners to register: the click policy table is {@link GuiListener}, registered separately. */
    public List<Listener> listeners() {
        return List.of(windowListener);
    }
}
