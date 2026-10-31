package dev.materione.plugin.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.RunBudget;
import dev.materione.behavior.run.RunScheduler;
import dev.materione.behavior.runtime.CooldownStore;
import dev.materione.behavior.runtime.RuleEngine;
import dev.materione.content.gui.CompiledGui;
import dev.materione.content.gui.GuiCompiler;
import dev.materione.content.gui.GuiComponents;
import dev.materione.content.gui.GuiDefinition;
import dev.materione.content.gui.GuiType;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.input.ClickRouter;
import dev.materione.plugin.gui.input.InputGuard;
import dev.materione.plugin.gui.input.LayerVisibility;
import dev.materione.plugin.gui.live.GuiLiveRefresh;
import dev.materione.plugin.gui.open.GuiLifecycle;
import dev.materione.plugin.gui.open.GuiOpener;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.session.GuiSessions;
import dev.materione.plugin.gui.tick.GuiTicker;
import dev.materione.plugin.gui.window.WindowHost;
import dev.materione.plugin.logic.BuiltinLogic;
import dev.materione.plugin.logic.LogicService;
import dev.materione.plugin.logic.LogicTestSupport;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.Yaml;

/**
 * The whole GUI runtime without a server: real sessions, opener, lifecycle, router, ticker and rule
 * engine over the real logic registries, with a recording {@link WindowHost} where the Bukkit inventory
 * would be and a stand-in player. GUIs are written as YAML and compiled by the real GUI compiler into the
 * live view, so a test exercises exactly the path a click takes in game: the authored rule, the click
 * map, the router, the engine, the action.
 *
 * <p>Two fake actions record what runs: {@code mark: text} renders its template against the run's context
 * and records it, and {@code fail} always returns a failure.
 */
public final class GuiFixture {

    public static final String PACK = "demo";

    /** What happened, in order: every action that ran as {@code mark:<text>}. */
    public final List<String> ran = new ArrayList<>();
    public final List<Diagnostic> diagnostics = new ArrayList<>();
    public final GuiSessions sessions = new GuiSessions();
    public final FakeWindows windows = new FakeWindows();
    public final ManualTicks ticks = new ManualTicks();
    public final GuiBridge bridge = new GuiBridge();
    public final List<String> messages = new ArrayList<>();
    public final Player player;
    public final Viewer viewer;
    public final GuiLookup guis;
    public final GuiEvents events;
    public final ClickRouter router;
    public final GuiOpener opener;
    public final GuiLifecycle lifecycle;
    public final GuiTicker ticker;
    public final GuiLiveRefresh refresh;
    public final RuleEngine engine;
    public final LogicService logic;
    public final InputGuard guard = new InputGuard();

    private final GuiType type;
    private final Map<NamespacedId, CompiledGui> compiled = new LinkedHashMap<>();
    private final AtomicReference<RuntimeGuis> view = new AtomicReference<>(RuntimeGuis.empty());
    public LayerVisibility visibility = LayerVisibility.ALL;

    public GuiFixture() {
        var registries = new RegistryService();
        logic = LogicTestSupport.newService(registries);
        Plugin owner = LogicTestSupport.owner("materione");
        BuiltinLogic.register(logic, owner, dev.materione.plugin.logic.support.ItemCatalog.none(), bridge);
        logic.actions().register(owner, NamespacedId.of("materione", "mark"), new Mark(ran));
        logic.actions().register(owner, NamespacedId.of("materione", "fail"), new Fail());

        var components = registries.registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        for (GuiComponent component : GuiComponents.builtins()) {
            components.register(NamespacedId.of("materione", component.name()), component, component.describe(), "test");
        }
        type = new GuiType(components);

        player = player(UUID.randomUUID(), "Probe", messages);
        viewer = Viewer.of(player);
        engine = new RuleEngine(new FlowInterpreter(new InlineScheduler(), RunBudget.DEFAULT, diagnostics::add),
                new CooldownStore());
        guis = lookup();
        events = new GuiEvents(engine::fire);
        router = new ClickRouter(guis, events, (session, layer) -> visibility.visible(session, layer), guard);
        ticker = new GuiTicker(guis, events, ticks, windows);
        lifecycle = new GuiLifecycle(guis, sessions, events, windows);
        opener = new GuiOpener(guis, sessions, events, windows, ticker, Logger.getAnonymousLogger());
        refresh = new GuiLiveRefresh(plugin(), null, guis, sessions, windows, lifecycle, ticker);
        bridge.bind(new GuiBridge.Services(guis, sessions, opener, lifecycle, refresh));
    }

    private GuiLookup lookup() {
        return new GuiLookup() {
            @Override
            public Optional<RuntimeGuis.Live> find(NamespacedId id) {
                return view.get().gui(id);
            }

            @Override
            public java.util.Collection<NamespacedId> ids() {
                return view.get().all().stream().map(RuntimeGuis.Live::id).toList();
            }
        };
    }

    // --- content ------------------------------------------------------------------

    /**
     * Publishes a GUI written as YAML (the key is the GUI's name; "on" may be written bare), compiling it
     * through the real GUI compiler and rule compiler into the live view. Replaces a GUI of the same id,
     * as a reload does.
     */
    public RuntimeGuis.Live gui(String yaml) {
        GuiDefinition definition = definition(yaml);
        compiled.put(definition.id(), GuiCompiler.compile(definition, 0xE200, reference -> Optional.empty()).model());
        publish();
        return view.get().gui(definition.id()).orElseThrow();
    }

    /** The parsed definition of a GUI written as YAML, not published. */
    public GuiDefinition definition(String yaml) {
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> document = (Map<String, Map<String, Object>>)
                new Yaml().load(yaml.replaceAll("(?m)^(\\s*(?:- )?)on:", "$1\"on\":"));
        var entry = document.entrySet().iterator().next();
        NamespacedId id = NamespacedId.of(PACK, entry.getKey());
        return (GuiDefinition) type.parse(id, entry.getValue(), Map.of()).orElseThrow();
    }

    /** Removes a GUI from the live view, as a reload that drops it does. */
    public void remove(String name) {
        compiled.remove(NamespacedId.of(PACK, name));
        publish();
    }

    private void publish() {
        view.set(RuntimeGuis.build(new LinkedHashMap<>(compiled), id -> Optional.empty(), id -> Optional.empty(),
                diagnostics::add, logic.lookup()));
    }

    public static NamespacedId id(String name) {
        return NamespacedId.of(PACK, name);
    }

    /** The currently live view, to hand to a refresh as the swap's result. */
    public RuntimeGuis view() {
        return view.get();
    }

    // --- fakes --------------------------------------------------------------------

    /** Records what the real window host would do to the server. */
    public static final class FakeWindows implements WindowHost {
        public final List<String> log = new ArrayList<>();
        public final Map<GuiSession, Shape> shapes = new LinkedHashMap<>();
        /** When set, opens wait here until released, like a host that defers a tick. */
        public final List<Runnable> deferred = new ArrayList<>();
        public boolean defer;
        public String failOpenWith;

        @Override
        public void open(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, Runnable opened,
                Consumer<String> failed) {
            show("open", session, gui, opened, failed);
        }

        @Override
        public void reopen(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, Runnable opened,
                Consumer<String> failed) {
            show("reopen", session, gui, opened, failed);
        }

        private void show(String what, GuiSession session, RuntimeGuis.Live gui, Runnable opened,
                Consumer<String> failed) {
            Runnable task = () -> {
                if (failOpenWith != null) {
                    failed.accept(failOpenWith);
                    return;
                }
                log.add(what + " " + gui.id().value());
                session.attach(this);
                shapes.put(session, new Shape(gui.title(), gui.rows()));
                opened.run();
            };
            if (defer) {
                deferred.add(task);
            } else {
                task.run();
            }
        }

        @Override
        public void close(Viewer viewer) {
            log.add("close");
        }

        @Override
        public void rewrite(Viewer viewer, GuiSession session, RuntimeGuis.Live gui) {
            log.add("rewrite " + gui.id().value());
        }

        @Override
        public void advance(Viewer viewer, GuiSession session, RuntimeGuis.Live gui) {
            log.add("advance " + gui.id().value());
        }

        @Override
        public Optional<Shape> shape(Viewer viewer, GuiSession session) {
            return Optional.ofNullable(shapes.get(session));
        }
    }

    /** A clock the ticker schedules against; {@link #advance} runs what has come due. */
    public static final class ManualTicks implements GuiTicker.TickScheduler {
        private record Task(long due, Runnable run) {}

        private final List<Task> tasks = new ArrayList<>();
        private long now;
        public final List<Long> periods = new ArrayList<>();

        @Override
        public void later(Viewer viewer, long ticks, Runnable task, Runnable retired) {
            periods.add(ticks);
            tasks.add(new Task(now + ticks, task));
        }

        /** Moves time forward, running every task that comes due, in order, one tick at a time. */
        public void advance(long ticks) {
            for (long i = 0; i < ticks; i++) {
                now++;
                var due = tasks.stream().filter(task -> task.due() <= now).toList();
                tasks.removeAll(due);
                due.forEach(task -> task.run().run());
            }
        }

        public int pending() {
            return tasks.size();
        }
    }

    /** Runs every step inline, never waiting: enough for flows without {@code wait}. */
    static final class InlineScheduler implements RunScheduler {
        @Override
        public boolean canRunInline(Object anchor, Threading thread) {
            return true;
        }

        @Override
        public Handle schedule(Object anchor, Threading thread, long delayTicks, Runnable task, Runnable dropped) {
            task.run();
            return Handle.NONE;
        }
    }

    /** {@code mark: "text ${gui.args.x}"}: records the rendered template. */
    static final class Mark implements ActionType<TemplateText> {
        private final List<String> ran;

        Mark(List<String> ran) {
            this.ran = ran;
        }

        @Override
        public String id() {
            return "mark";
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(new SelfDescription("Mark", "Records a rendered template",
                    List.of(ParameterSchema.of("text", ParameterType.STRING, true)), "1.0.0"),
                    LogicCategory.DATA, "text");
        }

        @Override
        public Threading threading() {
            return Threading.ANY;
        }

        @Override
        public TemplateText parse(ParamReader params) {
            return params.template("text");
        }

        @Override
        public ActionOutcome execute(TemplateText params, LogicContext context) {
            ran.add("mark:" + params.render(context));
            return ActionOutcome.OK;
        }
    }

    /** {@code fail}: always fails. */
    static final class Fail implements ActionType<String> {
        @Override
        public String id() {
            return "fail";
        }

        @Override
        public ActionDescription describe() {
            return ActionDescription.of(SelfDescription.of("Fail", "Always fails"), LogicCategory.DATA);
        }

        @Override
        public Threading threading() {
            return Threading.ANY;
        }

        @Override
        public String parse(ParamReader params) {
            return "";
        }

        @Override
        public ActionOutcome execute(String params, LogicContext context) {
            return ActionOutcome.failed("by design");
        }
    }

    /** A player that records the messages sent to it and answers the few queries a run context makes. */
    public static Player player(UUID id, String name, List<String> messages) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "isOnline" -> true;
                    case "isSneaking", "isOp" -> false;
                    case "getGameMode" -> GameMode.SURVIVAL;
                    case "getHealth" -> 20.0;
                    case "getLevel" -> 3;
                    case "sendMessage" -> {
                        messages.add(String.valueOf(args[0]));
                        yield null;
                    }
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "player " + name;
                    default -> throw new UnsupportedOperationException("player." + method.getName());
                });
    }

    /** A plugin stand-in that only has a logger. */
    public static Plugin plugin() {
        Logger logger = Logger.getAnonymousLogger();
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> logger;
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "plugin";
                    default -> throw new UnsupportedOperationException("plugin." + method.getName());
                });
    }
}
