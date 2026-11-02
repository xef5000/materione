package dev.materione.plugin.gui.window;

import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.platform.gui.PaperRichTitles;
import dev.materione.platform.gui.RichTitleTransport;
import dev.materione.platform.text.MaterioneText;
import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.input.GuiInput;
import dev.materione.plugin.gui.input.GuiJournal;
import dev.materione.plugin.gui.input.InputGuard;
import dev.materione.plugin.gui.input.InputReturns;
import dev.materione.plugin.gui.input.InputSlots;
import dev.materione.plugin.gui.render.GuiRenderer;
import dev.materione.plugin.gui.render.Rendered;
import dev.materione.plugin.gui.render.RenderReporter;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * The {@link WindowHost} over Bukkit inventories (gui.md §5). It builds a {@link GuiMenu}, renders it through
 * the {@link GuiRenderer} and opens it on the viewer's thread.
 *
 * <p>Changing a player's window while their click is being handled is unsafe, so when the
 * {@link InputGuard} says the viewer is mid-click, the open or close runs on their next tick through
 * the scheduling gateway; every other caller changes the window at once. The session is already
 * switched either way, so a click that arrives on the old window in between finds a stale session and
 * is ignored.
 *
 * <p>The player's items in input slots (logic.md §25) are carried, never dropped: a re-open moves them to the
 * new window, a rewrite keeps them in the slots that are still inputs (the rest go back to the player), and
 * a session's end gives them back.
 */
public final class BukkitWindowHost implements WindowHost {

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final GuiInput input;
    private final InputGuard guard;
    private final GuiJournal journal;
    private final GuiRenderer renderer;
    private final RichTitleTransport titles;
    private final RenderReporter reporter;
    private final TitleRefreshQueue titleRefreshes = new TitleRefreshQueue();
    private final HeldTitleRefresh heldTitles = new HeldTitleRefresh();
    private BiConsumer<Viewer, GuiSession> reconcile;

    public BukkitWindowHost(Plugin plugin, SchedulingGateway scheduling, GuiInput input, InputGuard guard,
            GuiJournal journal, GuiRenderer renderer) {
        this(plugin, scheduling, input, guard, journal, renderer, PaperRichTitles.create(null), RenderReporter.SILENT);
    }

    @Override
    public void bindReconciler(BiConsumer<Viewer, GuiSession> reconcile) {
        this.reconcile = java.util.Objects.requireNonNull(reconcile);
    }

    public BukkitWindowHost(Plugin plugin, SchedulingGateway scheduling, GuiInput input, InputGuard guard,
            GuiJournal journal, GuiRenderer renderer, RichTitleTransport titles, RenderReporter reporter) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.input = input;
        this.guard = guard;
        this.journal = journal;
        this.renderer = renderer;
        this.titles = titles;
        this.reporter = reporter;
    }

    @Override
    public Optional<String> unavailable(RuntimeGuis.Live gui) {
        if (gui.visuals() == null || titles.available()) {
            return Optional.empty();
        }
        String reason = "rich-title transport is unavailable; conditional title overlays cannot be opened";
        reporter.problem(DiagnosticCodes.GUI_DYNAMIC_LAYER_FAILED, gui.id(), "title", reason);
        return Optional.of(reason);
    }

    @Override
    public void open(Viewer viewer, GuiSession session, Supplier<Optional<RuntimeGuis.Live>> resolve,
            Consumer<RuntimeGuis.Live> opened, Consumer<String> failed) {
        titleRefreshes.invalidate(session);
        heldTitles.cancel(session);
        later(viewer, () -> {
            Optional<RuntimeGuis.Live> current = resolve.get();
            if (current.isEmpty()) {
                failed.accept(OPEN_DEFINITION_CHANGED);
                return;
            }
            RuntimeGuis.Live gui = current.get();
            show(viewer, session, gui, false, () -> opened.accept(gui), failed);
        }, () -> failed.accept("the player left before the window opened"));
    }

    @Override
    public void reopen(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, Runnable opened,
            Consumer<String> failed) {
        Object expectedWindow = session.window();
        heldTitles.cancel(session);
        titleRefreshes.update(session, true, guard.dispatching(viewer.id()),
                () -> show(viewer, session, gui, true, opened, failed),
                () -> reconcile(viewer, session, expectedWindow), (task, retired) -> later(viewer, task, retired));
    }

    private void show(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, boolean carryCursor,
            Runnable opened, Consumer<String> failed) {
        Optional<String> problem = unavailable(gui);
        if (problem.isPresent()) {
            failed.accept(problem.get());
            return;
        }
        Rendered next = renderer.render(viewer, session, gui, true, session.windowTicks());
        show(viewer, session, gui, carryCursor, next, opened, failed);
    }

    private void show(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, boolean carryCursor,
            Rendered next, Runnable opened, Consumer<String> failed) {
        Player player = viewer.player();
        Object previousWindow = session.window();
        Rendered previousRender = session.rendered();
        RuntimeGuis.Live previousDisplayed = session.displayed().orElse(null);
        GuiMenu menu = new GuiMenu(session, gui.rows(), next.title(), gui.title(), input);
        menu.inputSlots(slotsOf(gui, next));
        later(viewer, () -> {
            if (!player.isOnline()) {
                failed.accept("the player is offline");
                return;
            }
            if (!session.isOpen()) {
                failed.accept("the session ended before its window opened");
                return;
            }
            if (session.window() != previousWindow) {
                failed.accept("the window changed before its replacement opened");
                return;
            }
            // the cursor item is lifted off the old window first, so closing it does not hand the item
            // back to the inventory, and it is put back on the new window
            ItemStack carried = carryCursor ? player.getItemOnCursor() : null;
            boolean holdsItem = carried != null && !carried.getType().isAir();
            if (holdsItem) {
                player.setItemOnCursor(null);
            }
            // the player's items in the old window's input slots move to this window with the session
            Map<Integer, ItemStack> items = carryCursor && session.window() instanceof GuiMenu previous
                    && previous != menu ? InputReturns.take(previous) : Map.of();
            if (!carryCursor) {
                session.forgetWindow();
            }
            renderer.paint(menu.getInventory(), session, Rendered.EMPTY, next);
            session.attach(menu);
            session.displayed(gui);
            boolean shown;
            try {
                shown = player.openInventory(menu.getInventory()) != null;
            } catch (RuntimeException e) {
                shown = false;
            }
            if (holdsItem) {
                player.setItemOnCursor(carried);
            }
            if (!shown) {
                session.attach(previousWindow);
                session.remember(previousRender);
                session.displayed(previousDisplayed);
                if (previousWindow instanceof GuiMenu old && player.getOpenInventory().getTopInventory() == old.getInventory()) {
                    items.forEach((slot, item) -> old.getInventory().setItem(slot, item));
                } else {
                    items.values().forEach(item -> InputReturns.giveBack(player, item));
                }
                journal.mirror(player);
                failed.accept("the window was refused by the server (an InventoryOpenEvent was cancelled)");
                return;
            }
            placeInputs(player, menu, items);
            journal.mirror(player);
            plugin.getLogger().info("MATERIONE-GUI-OPENED id=" + gui.id() + " player=" + viewer.name()
                    + " rows=" + gui.rows() + " slots=" + gui.slots().size());
            opened.run();
        }, () -> failed.accept("the player left before the window opened"));
    }

    @Override
    public void close(Viewer viewer) {
        Player player = viewer.player();
        later(viewer, () -> {
            if (player.isOnline()) {
                player.closeInventory();
            }
        }, () -> { });
    }

    @Override
    public void closeOwned(Viewer viewer, GuiSession session) {
        heldTitles.cancel(session);
        later(viewer, () -> {
            if (menuOf(viewer, session).isPresent()) {
                viewer.player().closeInventory();
            }
        }, () -> { });
    }

    @Override
    public void releaseInputs(Viewer viewer, GuiSession session) {
        heldTitles.cancel(session);
        Player player = viewer.player();
        if (session.window() instanceof GuiMenu menu) {
            InputReturns.release(player, menu);
        }
        if (player != null) {
            journal.mirror(player); // the items are with the player now; the journal lets go of them in the same tick
        }
    }

    @Override
    public void rewrite(Viewer viewer, GuiSession session, RuntimeGuis.Live gui) {
        refresh(viewer, session, gui, true);
    }

    @Override
    public void advance(Viewer viewer, GuiSession session, RuntimeGuis.Live gui) {
        refresh(viewer, session, gui, false);
    }

    private void refresh(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, boolean full) {
        titleRefreshes.invalidate(session);
        menuOf(viewer, session).ifPresent(menu -> {
            if (gui.visuals() != null && !titles.available()) {
                Runnable close = () -> {
                    if (session.isOpen() && session.window() == menu && menuOf(viewer, session).isPresent()) {
                        reporter.problem(DiagnosticCodes.GUI_DYNAMIC_LAYER_FAILED, gui.id(), "title",
                                "rich-title transport is unavailable; closing the dynamic visual window");
                        viewer.player().closeInventory();
                    }
                };
                titleRefreshes.update(session, true, guard.dispatching(viewer.id()), close,
                        () -> reconcile(viewer, session, menu),
                        (task, retired) -> later(viewer, task, retired));
                return;
            }
            ItemStack carried = viewer.player().getItemOnCursor();
            if (heldTitles.pending(session) && carried != null && !carried.getType().isAir()) {
                return;
            }
            Rendered next = renderer.render(viewer, session, gui, full, session.windowTicks());
            boolean changed = !menu.title().equals(next.title());
            if (changed && carried != null && !carried.getType().isAir()) {
                awaitEmptyCursor(viewer, session, menu);
                return;
            }
            heldTitles.cancel(session);
            Set<Integer> now = slotsOf(gui, next);
            boolean ownershipChanged = !menu.inputSlots().equals(now);
            titleRefreshes.update(session, changed || ownershipChanged, guard.dispatching(viewer.id()), () -> {
                if (changed) {
                    String error = titles.send(viewer.player(), menu.getInventory(), MaterioneText.parseData(next.title()));
                    if (error != null) {
                        reporter.problem(DiagnosticCodes.GUI_DYNAMIC_LAYER_FAILED, gui.id(), "title", error);
                        viewer.player().closeInventory();
                        return;
                    }
                    menu.title(next.title());
                }
                if (!ownershipChanged) {
                    renderer.paint(menu.getInventory(), session, session.rendered(), next);
                } else {
                    Map<Integer, ItemStack> kept = InputReturns.take(menu);
                    renderer.paint(menu.getInventory(), session, session.rendered(), next);
                    menu.inputSlots(now);
                    placeInputs(viewer.player(), menu, kept);
                    journal.mirror(viewer.player());
                }
                session.displayed(gui);
            }, () -> reconcile(viewer, session, menu), (task, retired) -> later(viewer, task, retired));
        });
    }

    private void awaitEmptyCursor(Viewer viewer, GuiSession session, GuiMenu menu) {
        heldTitles.ensure(session, () -> {
            if (!session.isOpen() || session.window() != menu || menuOf(viewer, session).isEmpty()) {
                return;
            }
            ItemStack carried = viewer.player().getItemOnCursor();
            if (carried != null && !carried.getType().isAir()) {
                awaitEmptyCursor(viewer, session, menu);
            } else {
                reconcile(viewer, session, menu);
            }
        }, (task, retired) -> scheduling.executeEntityLater(plugin, viewer.player(), 1, task, retired));
    }

    private void reconcile(Viewer viewer, GuiSession session, Object expectedWindow) {
        if (session.isOpen() && session.window() == expectedWindow && menuOf(viewer, session).isPresent()) {
            java.util.Objects.requireNonNull(reconcile, "GUI live reconciler is not bound").accept(viewer, session);
        }
    }

    @Override
    public java.util.Optional<Shape> shape(Viewer viewer, GuiSession session) {
        return menuOf(viewer, session).map(menu -> new Shape(menu.baseTitle(), menu.rows()));
    }

    /** Puts retained inputs back and returns the stacks whose slots became covered. */
    private static void placeInputs(Player player, GuiMenu menu, Map<Integer, ItemStack> items) {
        InputMigration.move(items, menu.inputSlots(), menu.getInventory()::setItem,
                item -> InputReturns.giveBack(player, item));
    }

    private static Set<Integer> slotsOf(RuntimeGuis.Live gui, Rendered rendered) {
        return gui.inputs().effective(gui.clicks(), rendered).all().stream()
                .map(InputSlots.Input::slot).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private java.util.Optional<GuiMenu> menuOf(Viewer viewer, GuiSession session) {
        Player player = viewer.player();
        if (player == null || !player.isOnline()) {
            return java.util.Optional.empty();
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        return top.getHolder() instanceof GuiMenu menu && menu.session() == session
                ? java.util.Optional.of(menu)
                : java.util.Optional.empty();
    }

    /** Runs {@code task} now, or on the viewer's next tick when they are mid-click. */
    private void later(Viewer viewer, Runnable task, Runnable retired) {
        if (guard.dispatching(viewer.id())) {
            scheduling.executeEntityLater(plugin, viewer.player(), 1, task, retired);
        } else {
            task.run();
        }
    }
}
