package dev.materione.plugin.gui.window;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.content.gui.GuiCompiler;
import dev.materione.platform.gui.PaperRichTitles;
import dev.materione.plugin.gui.input.GuiInput;
import dev.materione.plugin.gui.input.GuiJournal;
import dev.materione.plugin.gui.input.InputGuard;
import dev.materione.plugin.gui.live.GuiLiveRefresh;
import dev.materione.plugin.gui.open.GuiLifecycle;
import dev.materione.plugin.gui.render.GuiRenderer;
import dev.materione.plugin.gui.render.RenderReporter;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.tick.GuiTicker;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real refresh and host scheduling with server-free inventory doubles. */
@Isolated
class ReopenCoalescingTest {
    @ParameterizedTest
    @ValueSource(strings = {"queued-new-shape", "queued-old-shape", "published-new-shape",
            "published-old-shape", "published-after-unsupported", "inplace-new-shape", "inplace-old-shape",
            "unsupported-new-shape", "unsupported-old-shape", "inplace-removed", "unsupported-removed",
            "reopen-removed", "inplace-replaced", "unsupported-retired"})
    void guardedShapeChangesApplyNewestLayoutAndConserveInputs(String scenario) throws Exception {
        boolean revertShape = scenario.equals("queued-old-shape") || scenario.equals("published-old-shape")
                || scenario.equals("published-after-unsupported") || scenario.endsWith("old-shape");
        boolean delayedRefresh = !scenario.startsWith("queued-");
        boolean removed = scenario.endsWith("-removed");
        boolean unsupported = scenario.contains("unsupported");
        boolean inplace = scenario.startsWith("inplace-") || scenario.startsWith("unsupported-");
        boolean replaced = scenario.endsWith("-replaced");
        boolean retired = scenario.endsWith("-retired");
        var oldServer = Bukkit.class.getDeclaredField("server");
        oldServer.setAccessible(true);
        Object previous = oldServer.get(null);
        var blockType = Material.class.getDeclaredField("blockType");
        blockType.setAccessible(true);
        Object previousBlockType = blockType.get(Material.DIAMOND);
        var top = new AtomicReference<Inventory>();
        var tasks = new ArrayList<Runnable>();
        var retirements = new ArrayList<Runnable>();
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var returns = new AtomicInteger();
        var cursor = new AtomicReference<ItemStack>(FakeStack.of(2));
        Server server = proxy(Server.class, (method, args) -> switch (method) {
            case "createInventory" -> {
                Object holder = args[0];
                int size = (int) args[1];
                var items = new ItemStack[size];
                yield proxy(Inventory.class, (name, a) -> switch (name) {
                    case "getHolder" -> holder;
                    case "getSize" -> size;
                    case "getItem" -> items[(int) a[0]];
                    case "setItem" -> { items[(int) a[0]] = (ItemStack) a[1]; yield null; }
                    default -> null;
                });
            }
            default -> null;
        });
        try {
            blockType.set(Material.DIAMOND, (java.util.function.Supplier<Object>) () -> null);
            oldServer.set(null, server);
            var fixture = new GuiFixture();
            var initial = fixture.gui(gui(1, 2));
            var published = new AtomicReference<RuntimeGuis.Live>(initial);
            GuiLookup guis = key -> java.util.Optional.ofNullable(published.get()).filter(live -> live.id().equals(key));
            UUID id = UUID.randomUUID();
            var session = new GuiSession(id, "Probe", initial.id(), Map.of());
            fixture.sessions.begin(session);
            var pdc = proxy(PersistentDataContainer.class, (name, args) -> null);
            PlayerInventory bag = proxy(PlayerInventory.class, (name, args) -> switch (name) {
                case "getItemInMainHand" -> FakeStack.of(1);
                case "addItem" -> { returns.incrementAndGet(); yield new HashMap<Integer, ItemStack>(); }
                default -> null;
            });
            var playerRef = new AtomicReference<Player>();
            InventoryView view = proxy(InventoryView.class, (name, args) -> switch (name) {
                case "getTopInventory" -> top.get();
                case "getBottomInventory" -> bag;
                case "getPlayer" -> playerRef.get();
                default -> null;
            });
            Player player = proxy(Player.class, (name, args) -> switch (name) {
                case "getUniqueId" -> id;
                case "getName" -> "Probe";
                case "isOnline" -> true;
                case "getGameMode" -> GameMode.SURVIVAL;
                case "getInventory" -> bag;
                case "getOpenInventory" -> view;
                case "getItemOnCursor" -> cursor.get();
                case "setItemOnCursor" -> { cursor.set((ItemStack) args[0]); yield null; }
                case "openInventory" -> { opens.incrementAndGet(); top.set((Inventory) args[0]); yield view; }
                case "closeInventory" -> { closes.incrementAndGet(); yield null; }
                case "getPersistentDataContainer" -> pdc;
                default -> null;
            });
            playerRef.set(player);
            Plugin plugin = proxy(Plugin.class, (name, args) -> switch (name) {
                case "getName" -> "Materione";
                case "getLogger" -> java.util.logging.Logger.getAnonymousLogger();
                default -> null;
            });
            SchedulingGateway scheduling = proxy(SchedulingGateway.class, (name, args) -> {
                if (name.equals("executeEntityLater")) {
                    tasks.add((Runnable) args[3]);
                    retirements.add((Runnable) args[4]);
                    return ScheduledHandle.NONE;
                }
                return null;
            });
            GuiInput input = new GuiInput() {
                public void click(GuiMenu menu, InventoryClickEvent event) {}
                public void outside(GuiMenu menu, InventoryClickEvent event) {}
            };
            var renderer = new GuiRenderer(RenderReporter.SILENT);
            var guard = new InputGuard();
            var journal = new GuiJournal(plugin, scheduling, fixture.sessions);
            var diagnostics = new ArrayList<String>();
            var host = new BukkitWindowHost(plugin, scheduling, input, guard, journal, renderer,
                    PaperRichTitles.create(null), (code, gui, layer, message) -> diagnostics.add(message));
            Viewer viewer = Viewer.of(player);
            var oldMenu = new GuiMenu(session, 1, initial.title(), input);
            top.set(oldMenu.getInventory());
            session.attach(oldMenu);
            var oldRendered = renderer.render(viewer, session, initial, true, 0);
            session.remember(oldRendered);
            oldMenu.inputSlots(initial.inputs().effective(initial.clicks(), oldRendered).all().stream()
                    .map(dev.materione.plugin.gui.input.InputSlots.Input::slot)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            oldMenu.getInventory().setItem(0, FakeStack.of(5));
            var ticker = new GuiTicker(guis, fixture.events, (who, ticks, task, onRetired) -> { }, host);
            var lifecycle = new GuiLifecycle(guis, fixture.sessions, fixture.events, host);
            var refresh = new GuiLiveRefresh(plugin, scheduling, guis, fixture.sessions, host, lifecycle, ticker);
            var latest = new AtomicReference<dev.materione.plugin.gui.RuntimeGuis.Live>();

            guard.during(id, () -> {
                int firstRows = inplace ? 1 : 2;
                RuntimeGuis.Live first = unsupported
                        ? dynamic(fixture, gui(firstRows, 0)) : fixture.gui(gui(firstRows, 0));
                if (unsupported) {
                    assertThat(first.visuals()).isNotNull();
                    if (inplace) first = withTitle(first, initial.title());
                }
                published.set(first);
                assertThat(refresh.refresh(viewer, session)).isTrue();
                latest.set(removed ? null : fixture.gui(gui(revertShape ? 1 : 3, 4)));
                published.set(latest.get());
                if (!delayedRefresh) {
                    assertThat(refresh.refresh(viewer, session)).isTrue();
                }
                assertThat(session.window()).isSameAs(oldMenu);
                assertThat(oldMenu.getInventory().getItem(0).getAmount()).isEqualTo(5);
                assertThat(opens.get()).isZero();
            });
            assertThat(tasks).hasSize(delayedRefresh ? 1 : 2);
            GuiSession replacement = null;
            if (replaced) {
                replacement = new GuiSession(id, "Probe", initial.id(), Map.of());
                fixture.sessions.begin(replacement);
            }
            if (retired) {
                retirements.forEach(Runnable::run);
            }
            tasks.forEach(Runnable::run);

            if (replaced || retired) {
                assertThat(session.isOpen()).isTrue();
                if (replaced) assertThat(fixture.sessions.current(id)).containsSame(replacement);
                assertThat(session.window()).isSameAs(oldMenu);
                assertThat(oldMenu.getInventory().getItem(0).getAmount()).isEqualTo(5);
                assertThat(cursor.get().getAmount()).isEqualTo(2);
                assertThat(opens.get()).isZero();
                assertThat(closes.get()).isZero();
                assertThat(returns.get()).isZero();
                assertThat(diagnostics).isEmpty();
                return;
            }
            if (removed) {
                assertThat(refresh.refresh(viewer, session)).isFalse();
                assertThat(session.isOpen()).isFalse();
                assertThat(fixture.sessions.current(id)).isEmpty();
                assertThat(oldMenu.getInventory().getItem(0)).isNull();
                assertThat(cursor.get().getAmount()).isEqualTo(2);
                assertThat(opens.get()).isZero();
                assertThat(closes.get()).isEqualTo(1);
                assertThat(returns.get()).isEqualTo(1);
                assertThat(diagnostics).isEmpty();
                return;
            }
            if (delayedRefresh) {
                assertThat(refresh.refresh(viewer, session)).isTrue();
            }
            assertThat(session.isOpen()).isTrue();
            assertThat(fixture.sessions.current(id)).containsSame(session);
            var newMenu = (GuiMenu) session.window();
            if (revertShape) {
                assertThat(newMenu).isSameAs(oldMenu);
            } else {
                assertThat(newMenu).isNotSameAs(oldMenu);
                assertThat(oldMenu.getInventory().getItem(0)).isNull();
            }
            assertThat(newMenu.rows()).isEqualTo(revertShape ? 1 : 3);
            assertThat(newMenu.baseTitle()).isEqualTo(latest.get().title());
            assertThat(top.get()).isSameAs(newMenu.getInventory());
            assertThat(newMenu.inputSlots()).contains(0).doesNotContain(4);
            assertThat(newMenu.getInventory().getItem(0).getAmount()).isEqualTo(5);
            assertThat(newMenu.getInventory().getItem(4).getAmount()).isEqualTo(1);
            assertThat(cursor.get().getAmount()).isEqualTo(2);
            assertThat(opens.get()).isEqualTo(revertShape ? 0 : 1);
            assertThat(closes.get()).isZero();
            assertThat(returns.get()).isZero();
            assertThat(diagnostics).isEmpty();
        } finally {
            oldServer.set(null, previous);
            blockType.set(Material.DIAMOND, previousBlockType);
        }
    }

    private static String gui(int rows, int displaySlot) {
        return """
                trade:
                  title: Trade%s
                  rows: %d
                  layers:
                    - {id: wells, type: slots, region: container, input: true}
                    - id: display
                      type: item
                      slot: %d
                      item: {player_slot: main_hand}
                """.formatted(rows, rows, displaySlot);
    }

    private static RuntimeGuis.Live dynamic(GuiFixture fixture, String yaml) {
        var definition = fixture.definition(yaml + """
                    - id: label
                      type: text
                      x: 8
                      y: 6
                      text: Dynamic
                      visible_if: "gui.session.show == true"
                """);
        var compiled = GuiCompiler.compile(definition, 0xE200, ref -> java.util.Optional.empty(),
                (layer, frame) -> 0xE201).model();
        return RuntimeGuis.build(Map.of(compiled.id(), compiled), id -> java.util.Optional.empty(),
                id -> java.util.Optional.empty(), ignored -> { }, fixture.logic.lookup())
                .gui(compiled.id()).orElseThrow();
    }

    private static RuntimeGuis.Live withTitle(RuntimeGuis.Live gui, String title) {
        return new RuntimeGuis.Live(gui.id(), gui.rows(), title, gui.accessibleTitle(), gui.slots(), gui.clicks(),
                gui.logic(), gui.inputs(), gui.open(), gui.placements(), gui.refresh(), gui.items(), gui.visuals());
    }

    private interface Call { Object apply(String name, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Object value = call.apply(method.getName(), args == null ? new Object[0] : args);
            if (value == null && method.getReturnType().isPrimitive()) {
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == double.class) return 0.0;
                if (method.getReturnType() == float.class) return 0.0f;
                if (method.getReturnType() == long.class) return 0L;
                return 0;
            }
            return value;
        }));
    }

    private static final class FakeStack extends ItemStack {
        private int amount;
        private FakeStack() { super(Material.DIAMOND); }
        static FakeStack of(int amount) {
            try {
                var type = Class.forName("sun.misc.Unsafe");
                var field = type.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                var stack = (FakeStack) type.getMethod("allocateInstance", Class.class)
                        .invoke(field.get(null), FakeStack.class);
                stack.amount = amount;
                return stack;
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }
        @Override public Material getType() { return Material.DIAMOND; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public int getMaxStackSize() { return 64; }
        @Override public boolean equals(Object other) {
            return other instanceof FakeStack that && amount == that.amount;
        }
        @Override public int hashCode() { return Integer.hashCode(amount); }
        @Override public org.bukkit.inventory.meta.ItemMeta getItemMeta() { return null; }
        @Override public boolean editMeta(java.util.function.Consumer<? super org.bukkit.inventory.meta.ItemMeta> edit) {
            return true;
        }
        @Override public FakeStack clone() { return of(amount); }
    }
}
