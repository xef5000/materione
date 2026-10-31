package dev.materione.plugin.gui;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.behavior.runtime.RuleEngine;
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
import dev.materione.plugin.gui.open.GuiOpener;
import dev.materione.plugin.gui.open.OpenCommandHandler;
import dev.materione.plugin.gui.open.OpenCommandRegistrar;
import dev.materione.plugin.gui.open.OpenSpecGate;
import dev.materione.plugin.gui.open.PlayerNames;
import dev.materione.plugin.gui.session.GuiSessions;
import dev.materione.plugin.gui.tick.GuiTicker;
import dev.materione.plugin.gui.window.BukkitWindowHost;
import dev.materione.plugin.gui.window.WindowHost;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * The GUI runtime, assembled (logic.md §9): sessions, events, router, window host, opener, lifecycle,
 * ticker, live refresh and the open commands, wired in one place so that every collaborator takes only what
 * it uses and none of them reaches for a singleton. The plugin builds one, binds its services into the
 * {@link GuiBridge} the actions hold, registers {@link #listeners()}, hands the opener to the API and the
 * command registrar to the server's COMMANDS event.
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
    private final OpenCommandRegistrar openCommands;
    private final GuiBridge.Services services;

    /**
     * @param live the registries that are live now
     * @param engine the rule engine: the GUI's triggers and the open requirement's deny steps run through it
     * @param visibility whether a layer is showing for a session (the router skips a hidden one)
     * @param diagnostics where open-command conflicts and tree re-sync problems are published
     */
    public GuiStack(Plugin plugin, SchedulingGateway scheduling, Supplier<RuntimeRegistries> live,
            RuleEngine engine, LayerVisibility visibility, Consumer<Diagnostic> diagnostics) {
        GuiLookup guis = GuiLookup.over(live);
        InputGuard guard = new InputGuard();
        RuleDispatcher dispatcher = engine::fire;
        GuiEvents events = new GuiEvents(dispatcher);
        this.router = new ClickRouter(guis, events, visibility, guard);
        WindowHost windows = new BukkitWindowHost(plugin, scheduling, new InventoryInput(sessions, router), guard);
        GuiTicker ticker = new GuiTicker(guis, events,
                (viewer, ticks, task, retired) -> scheduling.executeEntityLater(
                        plugin, viewer.player(), ticks, task, retired));
        this.lifecycle = new GuiLifecycle(guis, sessions, events, windows);
        this.opener = new GuiOpener(guis, sessions, events, windows, ticker, plugin.getLogger());
        opener.gate(new OpenSpecGate(OpenSpecGate.Permissions.live(), PlayerNames.server(),
                (rule, context, owner) -> engine.interpreter().start(rule, context, owner)));
        Supplier<RuntimeGuis> view = () -> {
            RuntimeRegistries registries = live.get();
            RuntimeGuis current = registries == null ? null : registries.view(RuntimeGuis.KEY);
            return current == null ? RuntimeGuis.empty() : current;
        };
        var handler = new OpenCommandHandler(plugin, scheduling, view, opener, PlayerNames.server());
        this.openCommands = new OpenCommandRegistrar(plugin, handler, view, diagnostics);
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

    /** The open commands: registered from the server's COMMANDS event, and re-synced after every content swap. */
    public OpenCommandRegistrar openCommands() {
        return openCommands;
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
