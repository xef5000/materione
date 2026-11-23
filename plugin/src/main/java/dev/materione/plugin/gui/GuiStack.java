package dev.materione.plugin.gui;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.behavior.runtime.RuleEngine;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.gui.PaperRichTitles;
import dev.materione.plugin.escrow.runtime.EscrowRuntime;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.event.RuleDispatcher;
import dev.materione.plugin.gui.input.ClickRouter;
import dev.materione.plugin.gui.input.GuiWindowListener;
import dev.materione.plugin.gui.input.InputGuard;
import dev.materione.plugin.gui.input.GuiJournal;
import dev.materione.plugin.gui.input.InputSlotClicks;
import dev.materione.plugin.gui.input.InputSlotOps;
import dev.materione.plugin.gui.input.InventoryInput;
import dev.materione.plugin.gui.input.StrictInputs;
import dev.materione.plugin.gui.input.LayerVisibility;
import dev.materione.plugin.gui.list.GuiPages;
import dev.materione.plugin.gui.live.GuiLiveRefresh;
import dev.materione.plugin.gui.open.GuiLifecycle;
import dev.materione.plugin.gui.render.GuiRenderer;
import dev.materione.plugin.gui.render.LoggingRenderReporter;
import dev.materione.plugin.gui.open.GuiOpener;
import dev.materione.plugin.commands.DocumentCommands;
import dev.materione.plugin.commands.OpenCommandHandler;
import dev.materione.plugin.commands.OpenDeclaration;
import dev.materione.plugin.commands.OpenCommandRegistrar;
import dev.materione.plugin.gui.open.GuiOpenGates;
import dev.materione.plugin.view.open.OpenSpecGate;
import dev.materione.plugin.commands.PlayerNames;
import dev.materione.plugin.view.ReadsIndex;
import dev.materione.plugin.view.ViewChanges;
import dev.materione.plugin.view.ViewKinds;
import dev.materione.plugin.view.ViewRefresher;
import dev.materione.plugin.view.ViewRenderStats;
import dev.materione.plugin.view.ViewScheduler;
import dev.materione.plugin.view.ViewWake;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import dev.materione.plugin.view.ViewRegistry;
import dev.materione.plugin.gui.tick.GuiTicker;
import dev.materione.plugin.gui.window.BukkitWindowHost;
import dev.materione.plugin.gui.window.WindowHost;
import dev.materione.plugin.logic.variable.VariableService;
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

    private final ViewRegistry views = new ViewRegistry();
    private final ViewKinds kinds = new ViewKinds();
    private static final long TICK_NANOS = 50_000_000L;

    private final ViewChanges changes;
    private final ViewScheduler scheduler;
    private final ViewRenderStats renderStats;
    private final GuiOpener opener;
    private final GuiLifecycle lifecycle;
    private final GuiLiveRefresh refresh;
    private final ClickRouter router;
    private final GuiWindowListener windowListener;
    private final OpenCommandRegistrar openCommands;
    private final OpenCommandHandler openHandler;
    private final GuiBridge.Services services;
    private final InputSlotClicks inputClicks;

    /**
     * @param live the registries that are live now
     * @param engine the rule engine: the GUI's triggers and the open requirement's deny steps run through it
     * @param visibility whether a layer is showing for a session (the router skips a hidden one)
     * @param diagnostics where open-command conflicts and tree re-sync problems are published
     * @param built the registries of the latest reload, applied or not: the command lifecycle registers from these,
     *     since it can run before the first apply (logic.md §23, decision 15)
     */
    public GuiStack(Plugin plugin, SchedulingGateway scheduling, Supplier<RuntimeRegistries> live,
            RuleEngine engine, LayerVisibility visibility, Consumer<Diagnostic> diagnostics,
            Supplier<RuntimeRegistries> built) {
        this(plugin, scheduling, live, engine, visibility, diagnostics, built, null, null);
    }

    public GuiStack(Plugin plugin, SchedulingGateway scheduling, Supplier<RuntimeRegistries> live,
            RuleEngine engine, LayerVisibility visibility, Consumer<Diagnostic> diagnostics,
            Supplier<RuntimeRegistries> built, VariableService variables) {
        this(plugin, scheduling, live, engine, visibility, diagnostics, built, null, variables);
    }

    public GuiStack(Plugin plugin, SchedulingGateway scheduling, Supplier<RuntimeRegistries> live,
            RuleEngine engine, LayerVisibility visibility, Consumer<Diagnostic> diagnostics,
            Supplier<RuntimeRegistries> built, PlatformDescriptor platform, VariableService variables) {
        this(plugin, scheduling, live, engine, visibility, diagnostics, built, platform, variables, null);
    }

    /** @param escrow the crash-safe input runtime ({@code durable: required} layers); null refuses those layers */
    public GuiStack(Plugin plugin, SchedulingGateway scheduling, Supplier<RuntimeRegistries> live,
            RuleEngine engine, LayerVisibility visibility, Consumer<Diagnostic> diagnostics,
            Supplier<RuntimeRegistries> built, PlatformDescriptor platform, VariableService variables,
            EscrowRuntime escrow) {
        Supplier<ReadsIndex> readsIndex = () -> {
            RuntimeRegistries current = live.get();
            return current == null ? null : current.view(ReadsIndex.KEY);
        };
        this.changes = new ViewChanges(readsIndex, views, kinds, ViewWake.NONE);
        if (variables != null) {
            variables.sessionStore(views.sessions()); // session variables: one more scope behind the store port
            variables.writeObserver(changes); // every write to a variable a view reads marks that view's viewers
        }
        StrictInputs strict = escrow == null ? StrictInputs.NONE : new StrictInputs(escrow);
        if (escrow != null) {
            escrow.bindWindows(strict);
        }
        GuiLookup guis = GuiLookup.over(live);
        InputGuard guard = new InputGuard();
        RuleDispatcher dispatcher = engine::fire;
        GuiEvents events = new GuiEvents(dispatcher, variables);
        this.router = new ClickRouter(guis, events, visibility, guard);
        GuiJournal journal = new GuiJournal(plugin, scheduling, views);
        var reporter = new LoggingRenderReporter(plugin.getLogger());
        GuiRenderer renderer = new GuiRenderer(reporter, variables);
        WindowHost windows = new BukkitWindowHost(
                plugin, scheduling, new InventoryInput(views, router), guard, journal, strict, renderer,
                PaperRichTitles.create(platform), reporter);
        GuiTicker ticker = new GuiTicker(guis, events,
                (viewer, ticks, task, retired) -> scheduling.executeEntityLater(
                        plugin, viewer.player(), ticks, task, retired),
                windows);
        this.lifecycle = new GuiLifecycle(guis, views, events, windows);
        this.opener = new GuiOpener(guis, views, events, windows, ticker, plugin.getLogger());
        opener.gate(GuiOpenGates.of(OpenSpecGate.Permissions.live(), PlayerNames.server(),
                (rule, context, owner) -> engine.interpreter().start(rule, context, owner), variables));
        Supplier<RuntimeGuis> view = guisOf(live);
        this.openHandler = new OpenCommandHandler(plugin, scheduling, view, opener, PlayerNames.server(), diagnostics);
        this.openCommands = new OpenCommandRegistrar(plugin, openHandler, view, guisOf(built), diagnostics, scheduling);
        this.refresh = new GuiLiveRefresh(plugin, scheduling, guis, views, windows, lifecycle, ticker);
        this.renderStats = new ViewRenderStats(System::currentTimeMillis);
        this.scheduler = new ViewScheduler(views, kinds, () -> System.nanoTime() / TICK_NANOS,
                (player, ticks, task, retired) -> {
                    Player online = Bukkit.getPlayer(player);
                    if (online != null) {
                        scheduling.executeEntityLater(plugin, online, ticks, task, retired);
                    }
                },
                id -> {
                    Player online = Bukkit.getPlayer(id);
                    return online == null ? null : Viewer.of(online);
                }, renderStats);
        changes.wake(scheduler);
        ViewRefresher refresher = new ViewRefresher(views, readsIndex, scheduler, id -> {
            Player online = Bukkit.getPlayer(id);
            return online != null && Bukkit.isOwnedByCurrentRegion(online);
        });
        this.windowListener = new GuiWindowListener(lifecycle, journal, strict);
        this.inputClicks = new InputSlotClicks(guis, views, events, guard, journal, strict);
        kinds.register(new GuiViewKind(guis, opener, lifecycle, refresh)); // the container GUI is the first view kind
        this.services = new GuiBridge.Services(guis, views, kinds, opener, lifecycle, refresh,
                new InputSlotOps(guis, journal, strict), new GuiPages(events, refresh), refresher);
    }

    /** The input-slot rules the click router consults (logic.md §25). */
    public InputSlotClicks inputClicks() {
        return inputClicks;
    }

    /** The write observer: marks the viewers a variable write reaches and calls the wake hook ({@link ViewChanges#wake}). */
    public ViewChanges changes() {
        return changes;
    }

    /** The scheduler the write observer wakes, and the explicit refresh entry points ({@link ViewScheduler#refreshNow}). */
    public ViewScheduler scheduler() {
        return scheduler;
    }

    /** The automatic renders per definition, for {@code /materione doctor}. */
    public ViewRenderStats renderStats() {
        return renderStats;
    }

    public ViewKinds kinds() {
        return kinds;
    }

    /** Every live viewer record, of every view kind. */
    public ViewRegistry views() {
        return views;
    }

    /** The one open path (logic.md §6.1). */
    public GuiOpener opener() {
        return opener;
    }

    /** The open commands: registered from the server's COMMANDS event, and re-synced after every content swap. */
    public OpenCommandRegistrar openCommands() {
        return openCommands;
    }

    /**
     * Gives the command tree the command documents (logic.md §40): what runs one when its name is typed, and where its
     * names are read from (the applied registries after a swap, the built ones at the server's COMMANDS event). Called
     * once the flow runtime exists; until then a name that belongs to a document runs nothing.
     */
    public void commandDocuments(
            DocumentCommands runner, Supplier<List<OpenDeclaration>> live, Supplier<List<OpenDeclaration>> built) {
        openHandler.documents(runner);
        openCommands.commandDocuments(live, built);
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

    /** The GUIs of a registries supplier, empty before there are any. */
    private static Supplier<RuntimeGuis> guisOf(Supplier<RuntimeRegistries> registries) {
        return () -> {
            RuntimeRegistries current = registries.get();
            RuntimeGuis view = current == null ? null : current.view(RuntimeGuis.KEY);
            return view == null ? RuntimeGuis.empty() : view;
        };
    }

    /** The listeners to register: the click policy table is {@link GuiListener}, registered separately. */
    public List<Listener> listeners() {
        return List.of(windowListener);
    }
}
