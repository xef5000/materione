package dev.materione.plugin.gui.window;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.content.gui.GuiCompiler;
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
import dev.materione.plugin.gui.render.GuiRenderer;
import dev.materione.plugin.gui.render.RenderReporter;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.session.GuiSessions;
import dev.materione.plugin.gui.session.CloseReason;
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

    private static void exercise(boolean cancel, boolean revert, boolean replace) throws Exception {
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
            var definition = fixture.definition("""
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
                    """);
            var compiled = GuiCompiler.compile(definition, 0xE200, ref -> Optional.empty()).model();
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
                case "getItemInMainHand" -> FakeStack.of(1);
                case "addItem" -> {
                    returns.incrementAndGet();
                    ItemStack[] items = (ItemStack[]) args[0];
                    bagItems[9] = items[0];
                    yield new java.util.HashMap<Integer, ItemStack>();
                }
                default -> null;
            });
            var playerRef = new AtomicReference<Player>();
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
            var host = new BukkitWindowHost(plugin, scheduling, input, guard, journal, renderer);
            var viewer = Viewer.of(player);
            var menu = new GuiMenu(session, 1, live.title(), input);
            session.attach(menu);
            session.remember(renderer.render(viewer, session, live, true, 0));
            menu.inputSlots(SetOf.inputs(live, session));
            topItems[0] = FakeStack.of(5);
            var events = new GuiEvents((rules, event) -> {
                session.vars().put("cover", true);
                host.rewrite(viewer, session, live);
                assertThat(menu.inputSlots()).contains(0);
                assertThat(topItems[0].getAmount()).isEqualTo(5);
                assertThat(returns.get()).isZero();
                assertThat(journalWrites.get()).isZero();
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
            tasks.remove(0).run();
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
    }

    private static final class SetOf {
        static java.util.Set<Integer> inputs(RuntimeGuis.Live live, GuiSession session) {
            return live.inputs().effective(live.clicks(), session.rendered()).all().stream()
                    .map(dev.materione.plugin.gui.input.InputSlots.Input::slot)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
