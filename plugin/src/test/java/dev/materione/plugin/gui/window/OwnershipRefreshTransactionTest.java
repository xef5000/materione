package dev.materione.plugin.gui.window;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.content.gui.GuiCompiler;
import dev.materione.platform.gui.RichTitleTransport;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.input.GuiInput;
import dev.materione.plugin.gui.input.GuiJournal;
import dev.materione.plugin.gui.input.InputGuard;
import dev.materione.plugin.gui.input.InputSlotClicks;
import dev.materione.plugin.gui.input.InputSlotOps;
import dev.materione.plugin.gui.input.ClickRouter;
import dev.materione.plugin.gui.input.ClickKind;
import dev.materione.plugin.gui.input.GuiClick;
import dev.materione.plugin.gui.input.LayerVisibility;
import dev.materione.plugin.gui.render.GuiRenderer;
import dev.materione.plugin.gui.render.RenderReporter;
import dev.materione.plugin.gui.open.GuiOpener;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.open.OpenResult;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.session.GuiSessions;
import dev.materione.plugin.gui.session.CloseReason;
import dev.materione.plugin.gui.tick.GuiTicker;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/** Exercises the actual pre-change event and shift-out path without starting a server. */
@Isolated
class OwnershipRefreshTransactionTest {
    @Test
    void shiftOutConservesItemsWhenPreEventShowsAConditionalItem() throws Exception {
        exercise(false, false, false);
    }

    @Test
    void cancelledShiftOutReturnsTheUntouchedStackExactlyOnceAfterTheEvent() throws Exception {
        exercise(true, false, false);
    }

    @Test
    void newerUncoverRefreshSupersedesTheQueuedCover() throws Exception {
        exercise(false, true, false);
    }

    @Test
    void queuedRefreshDoesNotTouchAReplacedSession() throws Exception {
        exercise(false, false, true);
    }

    @Test
    void titleAndOwnershipChangesWaitForCancelledShiftAndKeepTheMenuAndCursor() throws Exception {
        exercise(true, false, false, true, false);
    }

    @Test
    void failedTitleTransportDoesNotApplyNewOwnershipOrLoseInput() throws Exception {
        exercise(true, false, false, true, true);
    }

    @Test
    void revertedHeldTitleNeverFlashesAndKeepsInput() throws Exception {
        exercise(true, true, false, true, false);
    }

    @Test
    void heldTitleRetryCannotTouchAReplacementSession() throws Exception {
        exercise(false, false, true, true, false);
    }

    @Test
    void unsupportedTransportRejectsDynamicVisualWindowBeforeOpening() throws Exception {
        exercise(false, false, false, true, false, true);
    }

    @Test
    void heldPresentationKeepsOldRulesAndInputPolicyWithoutRerendering() throws Exception {
        exercise(true, false, false, true, false, false, true);
    }

    private static void exercise(boolean cancel, boolean revert, boolean replace) throws Exception {
        exercise(cancel, revert, replace, false, false);
    }

    private static void exercise(boolean cancel, boolean revert, boolean replace, boolean dynamic, boolean fail)
            throws Exception {
        exercise(cancel, revert, replace, dynamic, fail, false);
    }

    private static void exercise(boolean cancel, boolean revert, boolean replace, boolean dynamic, boolean fail,
            boolean unavailable) throws Exception {
        exercise(cancel, revert, replace, dynamic, fail, unavailable, false);
    }

    private static void exercise(boolean cancel, boolean revert, boolean replace, boolean dynamic, boolean fail,
            boolean unavailable, boolean publication) throws Exception {
        var oldServer = Bukkit.class.getDeclaredField("server");
        oldServer.setAccessible(true);
        Object previous = oldServer.get(null);
        var blockType = Material.class.getDeclaredField("blockType");
        blockType.setAccessible(true);
        Object previousBlockType = blockType.get(Material.DIAMOND);
        var topItems = new ItemStack[9];
        var bagItems = new ItemStack[36];
        var tasks = new ArrayList<Runnable>();
        var journalWrites = new AtomicInteger();
        var returns = new AtomicInteger();
        var sourceReads = new AtomicInteger();
        var topRef = new AtomicReference<Inventory>();
        Server server = proxy(Server.class, (method, args) -> switch (method) {
            case "createInventory" -> {
                var holder = args[0];
                Inventory top = proxy(Inventory.class, (name, a) -> switch (name) {
                    case "getHolder" -> holder;
                    case "getSize" -> 9;
                    case "getItem" -> topItems[(int) a[0]];
                    case "setItem" -> { topItems[(int) a[0]] = (ItemStack) a[1]; yield null; }
                    default -> null;
                });
                topRef.set(top);
                yield top;
            }
            case "getOnlinePlayers" -> List.of();
            case "getMaxPlayers" -> 20;
            default -> null;
        });
        try {
            // Paper's registry-backed ItemStack cannot be constructed without a running server.
            blockType.set(Material.DIAMOND, (java.util.function.Supplier<Object>) () -> null);
            oldServer.set(null, server);
            var fixture = new GuiFixture();
            String yaml = """
                    trade:
                      title: Trade
                      rows: 1
                      layers:
                        - {id: wells, type: slots, region: container, input: true}
                        - id: cover
                          type: item
                          slot: 0
                          item: {player_slot: main_hand}
                          visible_if: "gui.session.cover == true"
                      behavior:
                        - on: gui.slot_change
                          do: [gui.refresh]
                    """;
            if (dynamic) {
                yaml = yaml.replace("  behavior:", "    - id: label\n"
                        + "      type: text\n      x: 8\n      y: 6\n      text: DYNAMIC\n"
                        + "      visible_if: \"gui.session.cover == true\"\n  behavior:");
            }
            if (publication) {
                yaml = yaml.replace("  behavior:", "  behavior:\n"
                        + "    - on: gui.click\n      do: [{mark: OLD}]\n");
            }
            var definition = fixture.definition(yaml);
            var nextGlyph = new AtomicInteger(0xE201);
            var compiled = GuiCompiler.compile(definition, 0xE200, ref -> Optional.empty(),
                    (layer, frame) -> nextGlyph.getAndIncrement()).model();
            var live = RuntimeGuis.build(Map.of(compiled.id(), compiled), id -> Optional.empty(),
                    id -> Optional.empty(), ignored -> { }, fixture.logic.lookup()).gui(compiled.id()).orElseThrow();
            assertThat(live.items()).hasSize(1);
            assertThat(live.logic().rules("gui.slot_change")).hasSize(1);
            var playerId = UUID.randomUUID();
            var session = new GuiSession(playerId, "Probe", compiled.id(), Map.of());
            session.vars().put("cover", false);
            var sessions = new GuiSessions();
            sessions.begin(session);
            var pdc = proxy(PersistentDataContainer.class, (name, args) -> {
                if (name.equals("remove") || name.equals("set")) journalWrites.incrementAndGet();
                return null;
            });
            PlayerInventory bag = proxy(PlayerInventory.class, (name, args) -> switch (name) {
                case "getItem" -> bagItems[(int) args[0]];
                case "setItem" -> { bagItems[(int) args[0]] = (ItemStack) args[1]; yield null; }
                case "getItemInMainHand" -> { sourceReads.incrementAndGet(); yield FakeStack.of(1); }
                case "addItem" -> {
                    returns.incrementAndGet();
                    ItemStack[] items = (ItemStack[]) args[0];
                    bagItems[9] = items[0];
                    yield new java.util.HashMap<Integer, ItemStack>();
                }
                default -> null;
            });
            var playerRef = new AtomicReference<Player>();
            var cursor = new AtomicReference<ItemStack>(FakeStack.of(2));
            var opens = new AtomicInteger();
            var closes = new AtomicInteger();
            InventoryView view = proxy(InventoryView.class, (name, args) -> switch (name) {
                case "getTopInventory" -> topRef.get();
                case "getBottomInventory" -> bag;
                case "getPlayer" -> playerRef.get();
                case "getInventory" -> (int) args[0] < 9 ? topRef.get() : bag;
                case "convertSlot" -> args[0];
                case "getType" -> InventoryType.CHEST;
                default -> null;
            });
            Player player = proxy(Player.class, (name, args) -> switch (name) {
                case "getUniqueId" -> playerId;
                case "getName" -> "Probe";
                case "isOnline" -> true;
                case "getGameMode" -> GameMode.SURVIVAL;
                case "getInventory" -> bag;
                case "getOpenInventory" -> view;
                case "getItemOnCursor" -> cursor.get();
                case "openInventory" -> { opens.incrementAndGet(); yield null; }
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
            var journal = new GuiJournal(plugin, scheduling, sessions);
            var viewer = Viewer.of(player);
            var initial = renderer.render(viewer, session, live, true, 0);
            var menu = new GuiMenu(session, 1, initial.title(), input);
            var sends = new AtomicInteger();
            RichTitleTransport titles = new RichTitleTransport() {
                public boolean available() { return !unavailable; }
                public String send(Player who, Inventory expected, net.kyori.adventure.text.Component title) {
                    sends.incrementAndGet();
                    assertThat(who.getItemOnCursor()).isNull();
                    assertThat(expected).isSameAs(menu.getInventory());
                    assertThat(session.window()).isSameAs(menu);
                    assertThat(menu.inputSlots()).contains(0);
                    assertThat(amount(topItems[0])).isEqualTo(cancel ? 5 : 0);
                    return fail ? "unavailable" : null;
                }
            };
            var problems = new ArrayList<String>();
            var host = new BukkitWindowHost(plugin, scheduling, input, guard, journal, renderer,
                    titles, (code, gui, layer, message) -> problems.add(code.code() + ":" + message));
            var published = new AtomicReference<>(live);
            host.bindReconciler((who, current) -> {
                if (sessions.current(playerId).filter(s -> s == current).isPresent()) {
                    host.rewrite(who, current, published.get());
                }
            });
            session.attach(menu);
            session.remember(initial);
            session.displayed(live);
            menu.inputSlots(SetOf.inputs(live, session));
            if (publication) {
                String changedYaml = yaml.replace("input: true", "input: false")
                        .replace("  behavior:", "    - id: newer\n      type: button\n"
                                + "      x: 8\n      y: 18\n      width: 16\n      height: 16\n  behavior:")
                        .replace("do: [{mark: OLD}]", "layer: newer\n      do: [{mark: NEW}]");
                var glyph = new AtomicInteger(0xE201);
                var newerCompiled = GuiCompiler.compile(fixture.definition(changedYaml), 0xE200,
                        ref -> Optional.empty(), (layer, frame) -> glyph.getAndIncrement()).model();
                var newer = RuntimeGuis.build(Map.of(newerCompiled.id(), newerCompiled), id -> Optional.empty(),
                        id -> Optional.empty(), ignored -> { }, fixture.logic.lookup())
                        .gui(newerCompiled.id()).orElseThrow();
                published.set(newer);
                var dispatched = new ArrayList<List<dev.materione.behavior.rule.Rule>>();
                var eventRecorder = new GuiEvents((rules, event) -> {
                    var copy = new ArrayList<dev.materione.behavior.rule.Rule>();
                    rules.forEach(copy::add);
                    dispatched.add(copy);
                });
                var lookup = new GuiLookup() {
                    public Optional<RuntimeGuis.Live> find(dev.materione.api.NamespacedId id) {
                        return Optional.of(published.get());
                    }
                    public java.util.Collection<dev.materione.api.NamespacedId> ids() { return List.of(live.id()); }
                };
                var router = new ClickRouter(lookup, eventRecorder, LayerVisibility.ALL, guard);
                var clicks = new InputSlotClicks(lookup, sessions, eventRecorder, guard, journal);
                var operations = new InputSlotOps(lookup, journal);
                topItems[0] = FakeStack.of(5);
                session.vars().put("cover", true);
                host.rewrite(viewer, session, newer);
                assertThat(session.displayed()).containsSame(live);
                assertThat(clicks.policyOf(menu, player).slots().isInput(0)).isTrue();
                assertThat(operations.peek(session, 0).amount()).isEqualTo(5);
                assertThat(router.route(new GuiClick(viewer, session, 0, ClickKind.LEFT, 0)).layer()).isEmpty();
                assertThat(dispatched).containsExactly(live.logic().unboundClickRules());
                int readsAfterCandidate = sourceReads.get();
                for (int tick = 0; tick < 100; tick++) {
                    session.advanceWindowTicks(1);
                    host.advance(viewer, session, newer);
                    tasks.remove(0).run();
                    assertThat(tasks).hasSize(1);
                }
                assertThat(sourceReads.get()).isEqualTo(readsAfterCandidate);
                assertThat(session.windowTicks()).isEqualTo(100);
                assertThat(amount(topItems[0])).isEqualTo(5);
                assertThat(amount(cursor.get())).isEqualTo(2);
                cursor.set(null);
                tasks.remove(0).run();
                assertThat(sourceReads.get()).isEqualTo(readsAfterCandidate + 1);
                assertThat(session.displayed()).containsSame(newer);
                assertThat(clicks.policyOf(menu, player).slots().isInput(0)).isFalse();
                assertThat(operations.peek(session, 0).empty()).isTrue();
                assertThat(router.route(new GuiClick(viewer, session, 0, ClickKind.LEFT, 0)).layer()).isEqualTo("newer");
                assertThat(dispatched).hasSize(2);
                assertThat(dispatched.get(1)).isEqualTo(newer.logic().clickRules("newer"));
                assertThat(amount(topItems[0])).isEqualTo(1); // the new display item, not the returned input
                assertThat(amount(bagItems[9])).isEqualTo(5);
                assertThat(returns.get()).isEqualTo(1);
                assertThat(sends.get()).isEqualTo(1);
                return;
            }
            if (unavailable) {
                topItems[0] = FakeStack.of(5);
                GuiLookup lookup = new GuiLookup() {
                    public Optional<RuntimeGuis.Live> find(dev.materione.api.NamespacedId id) {
                        return id.equals(live.id()) ? Optional.of(live) : Optional.empty();
                    }
                    public java.util.Collection<dev.materione.api.NamespacedId> ids() { return List.of(live.id()); }
                };
                var ticker = new GuiTicker(lookup, fixture.events, (who, ticks, task, retired) -> { }, host);
                var opener = new GuiOpener(lookup, sessions, fixture.events, host, ticker,
                        java.util.logging.Logger.getAnonymousLogger());
                OpenResult result = opener.open(OpenRequest.of(viewer, live.id(), Map.of(), OpenRequest.Cause.API));
                assertThat(result.outcome()).isEqualTo(OpenResult.Outcome.FAILED);
                assertThat(result.reason()).contains("unavailable");
                assertThat(problems).singleElement().asString().contains("MTR-0073", "unavailable");
                assertThat(sessions.current(playerId)).containsSame(session);
                assertThat(session.isOpen()).isTrue();
                assertThat(session.window()).isSameAs(menu);
                assertThat(menu.inputSlots()).contains(0);
                assertThat(amount(topItems[0])).isEqualTo(5);
                assertThat(amount(bagItems[9])).isZero();
                assertThat(returns.get()).isZero();
                assertThat(journalWrites.get()).isZero();
                assertThat(sends.get()).isZero();
                assertThat(opens.get()).isZero();
                assertThat(closes.get()).isZero();
                var staticLive = new RuntimeGuis.Live(live.id(), live.rows(), initial.title(), live.accessibleTitle(),
                        live.slots(), live.clicks(), live.logic(), live.inputs(), live.open(), live.placements(),
                        live.refresh(), live.items());
                guard.during(playerId, () -> {
                    host.rewrite(viewer, session, live);
                    host.rewrite(viewer, session, staticLive);
                    assertThat(closes.get()).isZero();
                });
                assertThat(tasks).hasSize(1);
                tasks.forEach(Runnable::run);
                assertThat(closes.get()).isZero();
                assertThat(session.isOpen()).isTrue();
                assertThat(session.window()).isSameAs(menu);
                assertThat(amount(topItems[0])).isEqualTo(5);
                assertThat(returns.get()).isZero();
                return;
            }
            topItems[0] = FakeStack.of(5);
            var events = new GuiEvents((rules, event) -> {
                session.vars().put("cover", true);
                host.rewrite(viewer, session, live);
                assertThat(menu.inputSlots()).contains(0);
                assertThat(topItems[0].getAmount()).isEqualTo(5);
                assertThat(returns.get()).isZero();
                assertThat(journalWrites.get()).isZero();
                assertThat(sends.get()).isZero();
                if (revert) {
                    session.vars().put("cover", false);
                    host.rewrite(viewer, session, live);
                    assertThat(menu.inputSlots()).contains(0);
                    assertThat(returns.get()).isZero();
                    assertThat(journalWrites.get()).isZero();
                }
                if (cancel) event.requestCancel();
            });
            GuiLookup lookup = new GuiLookup() {
                public Optional<RuntimeGuis.Live> find(dev.materione.api.NamespacedId id) { return Optional.of(live); }
                public java.util.Collection<dev.materione.api.NamespacedId> ids() { return List.of(live.id()); }
            };
            var clicks = new InputSlotClicks(lookup, sessions, events, guard, journal);
            var event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0,
                    ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            clicks.shiftOut(menu, event);
            assertThat(tasks).hasSize(1);
            assertThat(menu.inputSlots()).contains(0);
            assertThat(returns.get()).isZero();
            assertThat(journalWrites.get()).isEqualTo(cancel ? 0 : 1);
            assertThat(amount(topItems[0]) + amount(bagItems[9])).isEqualTo(5);
            assertThat(amount(topItems[0])).isEqualTo(cancel ? 5 : 0);
            if (replace) {
                sessions.end(session, CloseReason.REPLACED);
                sessions.begin(new GuiSession(playerId, "Probe", compiled.id(), Map.of()));
            }
            if (dynamic) {
                var displayed = session.rendered();
                if (revert || replace) {
                    tasks.remove(0).run();
                    assertThat(session.rendered()).isSameAs(displayed);
                    assertThat(menu.title()).isEqualTo(initial.title());
                    assertThat(menu.inputSlots()).contains(0);
                    assertThat(sends.get()).isZero();
                    assertThat(returns.get()).isZero();
                    assertThat(cursor.get().getAmount()).isEqualTo(2);
                    return;
                }
                session.advanceWindowTicks(13);
                tasks.remove(0).run();
                assertThat(session.rendered()).isSameAs(displayed);
                assertThat(menu.title()).isEqualTo(initial.title());
                assertThat(session.windowTicks()).isEqualTo(13);
                assertThat(menu.inputSlots()).contains(0);
                assertThat(amount(topItems[0])).isEqualTo(5);
                assertThat(returns.get()).isZero();
                assertThat(sends.get()).isZero();
                assertThat(cursor.get().getAmount()).isEqualTo(2);
                assertThat(tasks).hasSize(1);
                session.vars().put("cover", false);
                session.vars().put("cover", true);
                if (!fail) {
                    var newestGlyph = new AtomicInteger(0xE201);
                    var newest = GuiCompiler.compile(fixture.definition(yaml.replace("DYNAMIC", "LATEST")),
                            0xE200, ref -> Optional.empty(), (layer, frame) -> newestGlyph.getAndIncrement()).model();
                    published.set(RuntimeGuis.build(Map.of(newest.id(), newest), id -> Optional.empty(),
                            id -> Optional.empty(), ignored -> { }, fixture.logic.lookup()).gui(newest.id()).orElseThrow());
                }
                cursor.set(null);
            }
            tasks.remove(0).run();
            if (dynamic) {
                assertThat(sends.get()).isEqualTo(1);
                assertThat(session.window()).isSameAs(menu);
                assertThat(player.getItemOnCursor()).isNull();
                assertThat(opens.get()).isZero();
                if (fail) {
                    assertThat(problems).singleElement().asString().contains("MTR-0073", "unavailable");
                    assertThat(closes.get()).isEqualTo(1);
                    assertThat(menu.title()).isEqualTo(initial.title());
                    assertThat(menu.inputSlots()).contains(0);
                    assertThat(amount(topItems[0])).isEqualTo(5);
                    assertThat(returns.get()).isZero();
                    return;
                }
                assertThat(closes.get()).isZero();
                assertThat(menu.title()).isNotEqualTo(initial.title());
                assertThat(menu.title()).contains("LATEST").doesNotContain("DYNAMIC");
            }
            if (revert || replace) {
                assertThat(menu.inputSlots()).contains(0);
                assertThat(amount(topItems[0])).isZero();
                assertThat(amount(bagItems[9])).isEqualTo(5);
                assertThat(returns.get()).isZero();
                assertThat(journalWrites.get()).isEqualTo(1);
                return;
            }
            assertThat(menu.inputSlots()).doesNotContain(0);
            assertThat(returns.get()).isEqualTo(cancel ? 1 : 0);
            assertThat(journalWrites.get()).isEqualTo(cancel ? 1 : 2);
            assertThat(amount(topItems[0])).isEqualTo(1); // the visible display item is not player-owned
            assertThat(amount(bagItems[9])).isEqualTo(5);
            if (dynamic) {
                host.rewrite(viewer, session, published.get());
                assertThat(sends.get()).isEqualTo(1);
                assertThat(session.window()).isSameAs(menu);
            }
        } finally {
            oldServer.set(null, previous);
            blockType.set(Material.DIAMOND, previousBlockType);
        }
    }

    private static int amount(ItemStack stack) { return stack == null ? 0 : stack.getAmount(); }

    private interface Call { Object invoke(String name, Object[] args); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Object value = call.invoke(method.getName(), args == null ? new Object[0] : args);
            if (value == null && method.getReturnType().isPrimitive()) {
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == double.class) return 0.0;
                if (method.getReturnType() == float.class) return 0.0f;
                if (method.getReturnType() == long.class) return 0L;
                return 0;
            }
            return value;
        });
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
        @Override public ItemMeta getItemMeta() { return null; }
        @Override public boolean editMeta(java.util.function.Consumer<? super ItemMeta> edit) { return true; }
        @Override public FakeStack clone() { return of(amount); }
        @Override public boolean equals(Object other) { return other instanceof FakeStack stack && amount == stack.amount; }
        @Override public int hashCode() { return amount; }
    }

    private static final class SetOf {
        static java.util.Set<Integer> inputs(RuntimeGuis.Live live, GuiSession session) {
            return live.inputs().effective(live.clicks(), session.rendered()).all().stream()
                    .map(dev.materione.plugin.gui.input.InputSlots.Input::slot)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
