package dev.materione.plugin.gui.window;

import static dev.materione.plugin.gui.window.ReopenCoalescingTest.proxy;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.platform.gui.PaperRichTitles;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.input.GuiInput;
import dev.materione.plugin.gui.input.GuiJournal;
import dev.materione.plugin.gui.input.InputGuard;
import dev.materione.plugin.gui.live.GuiLiveRefresh;
import dev.materione.plugin.gui.open.GuiLifecycle;
import dev.materione.plugin.gui.open.GuiOpener;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.render.GuiRenderer;
import dev.materione.plugin.gui.render.RenderReporter;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.tick.GuiTicker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

/** Real opener, host, and reload callback while an initial open is deferred by a click. */
@Isolated
class InitialOpenPublicationTest {
    @ParameterizedTest
    @ValueSource(strings = {"new-layout", "removed", "unsupported", "newer-session"})
    void deferredOpenUsesPublishedDefinitionAndNeverDisturbsNewerOwnership(String scenario) throws Exception {
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object previousServer = serverField.get(null);
        var blockType = Material.class.getDeclaredField("blockType");
        blockType.setAccessible(true);
        Object previousBlockType = blockType.get(Material.DIAMOND);
        var top = new AtomicReference<Inventory>();
        var tasks = new ArrayList<Runnable>();
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var returned = new AtomicInteger();
        var cursor = new AtomicReference<ItemStack>(ReopenCoalescingTest.FakeStack.of(2));
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
            serverField.set(null, server);
            var fixture = new GuiFixture();
            var previousGui = fixture.gui(gui("old", 1, 0, "old"));
            var first = fixture.gui(gui("trade", 1, 0, "A"));
            var newerGui = fixture.gui(gui("newer", 1, 0, "newer"));
            var published = new AtomicReference<RuntimeGuis.Live>(first);
            GuiLookup guis = id -> id.equals(first.id()) ? Optional.ofNullable(published.get()) : fixture.guis.find(id);
            UUID id = UUID.randomUUID();
            var pdc = proxy(PersistentDataContainer.class, (name, args) -> null);
            PlayerInventory bag = proxy(PlayerInventory.class, (name, args) -> switch (name) {
                case "getItemInMainHand" -> ReopenCoalescingTest.FakeStack.of(1);
                case "addItem" -> {
                    for (ItemStack item : (ItemStack[]) args[0]) returned.addAndGet(item.getAmount());
                    yield new HashMap<Integer, ItemStack>();
                }
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
                case "closeInventory" -> { closes.incrementAndGet(); top.set(null); yield null; }
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
            var guard = new InputGuard();
            var diagnostics = new ArrayList<String>();
            var host = new BukkitWindowHost(plugin, scheduling, input, guard,
                    new GuiJournal(plugin, scheduling, fixture.sessions), new GuiRenderer(RenderReporter.SILENT),
                    PaperRichTitles.create(null), (code, gui, layer, message) -> diagnostics.add(message));
            Viewer viewer = Viewer.of(player);
            var previous = new GuiSession(id, "Probe", previousGui.id(), Map.of());
            fixture.sessions.begin(previous);
            var oldMenu = new GuiMenu(previous, 1, previousGui.title(), input);
            oldMenu.inputSlots(Set.of(0));
            oldMenu.getInventory().setItem(0, ReopenCoalescingTest.FakeStack.of(5));
            previous.attach(oldMenu);
            top.set(oldMenu.getInventory());
            var ticker = new GuiTicker(guis, fixture.events, (who, ticks, task, retired) -> { }, host);
            var lifecycle = new GuiLifecycle(guis, fixture.sessions, fixture.events, host);
            var refresh = new GuiLiveRefresh(plugin, scheduling, guis, fixture.sessions, host, lifecycle, ticker);
            var opener = new GuiOpener(guis, fixture.sessions, fixture.events, host, ticker,
                    java.util.logging.Logger.getAnonymousLogger());
            var pending = new AtomicReference<GuiSession>();

            guard.during(id, () -> {
                var result = opener.open(OpenRequest.of(viewer, first.id(), Map.of(), OpenRequest.Cause.API));
                assertThat(result.ok()).isTrue();
                pending.set(result.session());
                assertThat(oldMenu.getInventory().getItem(0)).isNull();
                assertThat(returned.get()).isEqualTo(5);
                published.set(scenario.equals("removed") ? null
                        : scenario.equals("unsupported") ? ReopenCoalescingTest.dynamic(fixture, base("trade", 3, 4))
                        : fixture.gui(gui("trade", 3, 4, "B")));
                try {
                    var method = GuiLiveRefresh.class.getDeclaredMethod("refreshViewer",
                            Viewer.class, GuiSession.class, Optional.class);
                    method.setAccessible(true);
                    method.invoke(refresh, viewer, pending.get(), Optional.ofNullable(published.get()));
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                assertThat(pending.get().window()).isNull();
                assertThat(opens.get()).isZero();
            });
            assertThat(tasks).hasSize(1);
            GuiSession replacement = null;
            if (scenario.equals("newer-session")) {
                replacement = opener.open(OpenRequest.of(viewer, newerGui.id(), Map.of(), OpenRequest.Cause.API))
                        .session();
                assertThat(replacement).isNotNull();
                var replacementMenu = (GuiMenu) replacement.window();
                assertThat(replacementMenu.inputSlots()).contains(1);
                replacementMenu.getInventory().setItem(1, ReopenCoalescingTest.FakeStack.of(7));
            }
            tasks.get(0).run();

            assertThat(returned.get()).isEqualTo(5);
            assertThat(cursor.get().getAmount()).isEqualTo(2);
            assertThat(oldMenu.getInventory().getItem(0)).isNull();
            assertThat(fixture.ran).doesNotContain("mark:A");
            if (scenario.equals("new-layout")) {
                assertThat(fixture.sessions.current(id)).containsSame(pending.get());
                var menu = (GuiMenu) pending.get().window();
                assertThat(menu.rows()).isEqualTo(3);
                assertThat(menu.baseTitle()).isEqualTo(published.get().title());
                assertThat(menu.inputSlots()).contains(0).doesNotContain(4);
                assertThat(menu.getInventory().getItem(4).getAmount()).isEqualTo(1);
                assertThat(top.get()).isSameAs(menu.getInventory());
                assertThat(fixture.ran).containsExactly("mark:B");
                assertThat(opens.get()).isEqualTo(1);
                assertThat(closes.get()).isZero();
                assertThat(diagnostics).isEmpty();
            } else if (scenario.equals("newer-session")) {
                assertThat(fixture.sessions.current(id)).containsSame(replacement);
                assertThat(replacement.isOpen()).isTrue();
                assertThat(pending.get().isOpen()).isFalse();
                assertThat(top.get()).isSameAs(((GuiMenu) replacement.window()).getInventory());
                assertThat(((GuiMenu) replacement.window()).getInventory().getItem(1).getAmount()).isEqualTo(7);
                assertThat(fixture.ran).containsExactly("mark:newer");
                assertThat(opens.get()).isEqualTo(1);
                assertThat(closes.get()).isZero();
                assertThat(diagnostics).isEmpty();
            } else {
                assertThat(fixture.sessions.current(id)).isEmpty();
                assertThat(pending.get().isOpen()).isFalse();
                assertThat(top.get()).isNull();
                assertThat(fixture.ran).isEmpty();
                assertThat(opens.get()).isZero();
                assertThat(closes.get()).isEqualTo(1);
                assertThat(diagnostics).hasSize(scenario.equals("unsupported") ? 1 : 0);
            }
        } finally {
            serverField.set(null, previousServer);
            blockType.set(Material.DIAMOND, previousBlockType);
        }
    }

    private static String base(String id, int rows, int displaySlot) {
        return """
                %s:
                  title: Trade%s
                  rows: %d
                  layers:
                    - {id: wells, type: slots, region: container, input: true}
                    - id: display
                      type: item
                      slot: %d
                      item: {player_slot: main_hand}
                """.formatted(id, rows, rows, displaySlot);
    }

    private static String gui(String id, int rows, int displaySlot, String mark) {
        return base(id, rows, displaySlot) + """
                  behavior:
                    - on: gui.open
                      do:
                        - mark: %s
                """.formatted(mark);
    }
}
