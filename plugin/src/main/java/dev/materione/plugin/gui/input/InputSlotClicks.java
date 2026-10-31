package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.PluginGui;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.session.GuiSessions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The input-slot side of the click policy (logic.md §6.5, §25): what a click on a window's input slots,
 * or on the viewer's inventory while the window is open, does. The listener asks for the window's policy
 * and the click's target, and hands the click here; this class checks the change, fires the rules and
 * applies the moves the server does not do for us.
 *
 * <p>Two kinds of change. A single-slot click on an input slot is left to the server, after the check:
 * {@link #checked} predicts the change ({@link SlotPolicy}), the slot's {@code accept:} and
 * {@code gui.slot_change} see it, and a refusal cancels the click. Every move that spans several slots
 * (a shift-click in either direction, a double-click collection) is planned ({@link Transfers}), checked
 * slot by slot, and applied here, all or nothing, so no item is ever created or lost by the server
 * placing it in a display slot. The drag is the server's, checked the same way from its own new items.
 *
 * <p>Every method runs on the viewer's thread, inside {@link dev.materione.plugin.gui.input.InputGuard}
 * so a rule that closes or re-opens the window defers it past this click.
 */
public final class InputSlotClicks {

    private final GuiLookup guis;
    private final GuiSessions sessions;
    private final GuiEvents events;
    private final InputGuard guard;
    private final GuiJournal journal;

    public InputSlotClicks(GuiLookup guis, GuiSessions sessions, GuiEvents events, InputGuard guard,
            GuiJournal journal) {
        this.guis = guis;
        this.sessions = sessions;
        this.events = events;
        this.guard = guard;
        this.journal = journal;
    }

    /** The policy of a window for the viewer who clicked it, read from the live view now. */
    public WindowPolicy policyOf(PluginGui gui, HumanEntity who) {
        if (!(gui instanceof GuiMenu menu)) {
            return WindowPolicy.LEGACY;
        }
        return liveOf(menu, who).map(live -> WindowPolicy.live(live.inputs())).orElse(WindowPolicy.STALE);
    }

    /**
     * Fires {@code gui.click_inventory} for a click in the viewer's own inventory (logic.md §6.2).
     *
     * @return true when a rule cancelled the click
     */
    public boolean notifyInventory(PluginGui gui, InventoryClickEvent event) {
        if (!(gui instanceof GuiMenu menu) || !(event.getWhoClicked() instanceof Player player)) {
            return false;
        }
        var click = ClickClassifier.classify(event.getClick(), event.getHotbarButton());
        Optional<RuntimeGuis.Live> live = liveOf(menu, player);
        if (click.isEmpty() || live.isEmpty()) {
            return false;
        }
        Viewer viewer = Viewer.of(player);
        String item = BukkitStacks.view(event.getCurrentItem()).id();
        return during(player.getUniqueId(), () -> events.clickInventory(viewer, menu.session(), live.get(),
                click.get().kind(), event.getSlot(), click.get().key(), item));
    }

    /**
     * Whether a single-slot click on an input slot may go ahead. The server applies it when this returns
     * true; a false means the listener cancels it, and nothing changes.
     */
    public boolean checked(GuiMenu menu, InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return false;
        }
        Optional<RuntimeGuis.Live> live = liveOf(menu, player);
        int slot = event.getRawSlot();
        Optional<InputSlots.Input> input = live.flatMap(view -> view.inputs().at(slot));
        if (live.isEmpty() || input.isEmpty()) {
            return false;
        }
        int button = event.getHotbarButton();
        ItemStack swap = button == SlotPolicy.OFFHAND_BUTTON
                ? player.getInventory().getItemInOffHand()
                : button >= 0 && button <= 8 ? player.getInventory().getItem(button) : null;
        StackView before = BukkitStacks.view(menu.getInventory().getItem(slot));
        SlotPolicy.Change change = SlotPolicy.change(event.getAction(), button, before,
                BukkitStacks.view(event.getCursor()), BukkitStacks.view(swap));
        if (change == null) {
            return false;
        }
        Viewer viewer = Viewer.of(player);
        boolean allowed = during(player.getUniqueId(), () ->
                allows(viewer, menu.session(), live.get(), input.get(), slot, before, change));
        if (allowed) {
            journal.mirrorNextTick(player); // the server applies the click after this handler returns
        }
        return allowed;
    }

    /**
     * A shift-click from the viewer's inventory into the window's input slots: the item goes into the
     * input slots that accept it, similar stacks first, then empty ones, in slot order.
     */
    public void shiftIn(GuiMenu menu, InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Optional<RuntimeGuis.Live> live = liveOf(menu, player);
        ItemStack source = event.getCurrentItem();
        StackView src = BukkitStacks.view(source);
        if (live.isEmpty() || src.empty()) {
            return;
        }
        Viewer viewer = Viewer.of(player);
        GuiSession session = menu.session();
        during(player.getUniqueId(), () -> {
            Inventory top = menu.getInventory();
            var cells = new ArrayList<Transfers.Cell>();
            for (InputSlots.Input input : live.get().inputs().all()) {
                ItemStack item = top.getItem(input.slot());
                if (events.accepts(viewer, session, input, input.slot(), src, BukkitStacks.view(item))) {
                    cells.add(new Transfers.Cell(input.slot(), BukkitStacks.view(item).amount(),
                            BukkitStacks.similar(item, source)));
                }
            }
            Transfers.Plan plan = Transfers.put(src.amount(), src.maxStack(), cells);
            if (plan.moved() == 0) {
                return null;
            }
            for (Transfers.Move move : plan.moves()) {
                InputSlots.Input input = live.get().inputs().at(move.slot()).orElseThrow();
                StackView before = BukkitStacks.view(top.getItem(move.slot()));
                StackView after = before.empty() ? src.withAmount(move.count())
                        : before.withAmount(before.amount() + move.count());
                if (events.slotChange(viewer, session, live.get(), input.layer(), move.slot(), before, after)) {
                    return null; // all or nothing: a refused slot refuses the whole shift
                }
            }
            for (Transfers.Move move : plan.moves()) {
                top.setItem(move.slot(), grown(top.getItem(move.slot()), source, move.count()));
            }
            event.getClickedInventory().setItem(event.getSlot(), BukkitStacks.withAmount(source, src.amount() - plan.moved()));
            journal.mirror(player);
            return null;
        });
    }

    /**
     * A shift-click from an input slot into the viewer's inventory: the item goes into the viewer's storage,
     * similar stacks first, then empty slots (main inventory, then hotbar).
     */
    public void shiftOut(GuiMenu menu, InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Optional<RuntimeGuis.Live> live = liveOf(menu, player);
        int slot = event.getRawSlot();
        Optional<InputSlots.Input> input = live.flatMap(view -> view.inputs().at(slot));
        ItemStack item = menu.getInventory().getItem(slot);
        StackView before = BukkitStacks.view(item);
        if (live.isEmpty() || input.isEmpty() || before.empty()) {
            return;
        }
        Viewer viewer = Viewer.of(player);
        GuiSession session = menu.session();
        during(player.getUniqueId(), () -> {
            PlayerInventory storage = player.getInventory();
            Transfers.Plan plan = Transfers.put(before.amount(), before.maxStack(), storageCells(storage, item));
            if (plan.moved() == 0) {
                return null;
            }
            int left = before.amount() - plan.moved();
            StackView after = new StackView(left > 0 ? before.id() : "", left, before.maxStack());
            if (events.slotChange(viewer, session, live.get(), input.get().layer(), slot, before, after)) {
                return null;
            }
            for (Transfers.Move move : plan.moves()) {
                storage.setItem(move.slot(), grown(storage.getItem(move.slot()), item, move.count()));
            }
            menu.getInventory().setItem(slot, BukkitStacks.withAmount(item, left));
            journal.mirror(player);
            return null;
        });
    }

    /**
     * A double-click collection onto the cursor: matching items come from the input slots first, then from
     * the viewer's inventory, up to a full stack. Display slots of the window are never a source.
     */
    public void collect(GuiMenu menu, InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Optional<RuntimeGuis.Live> live = liveOf(menu, player);
        ItemStack cursor = event.getCursor();
        StackView cur = BukkitStacks.view(cursor);
        if (live.isEmpty() || cur.empty() || cur.amount() >= cur.maxStack()) {
            return;
        }
        Viewer viewer = Viewer.of(player);
        GuiSession session = menu.session();
        during(player.getUniqueId(), () -> {
            Inventory top = menu.getInventory();
            var topCells = new ArrayList<Transfers.Cell>();
            for (InputSlots.Input input : live.get().inputs().all()) {
                ItemStack item = top.getItem(input.slot());
                topCells.add(new Transfers.Cell(input.slot(), BukkitStacks.view(item).amount(),
                        BukkitStacks.similar(item, cursor)));
            }
            int room = cur.maxStack() - cur.amount();
            Transfers.Plan fromWindow = Transfers.take(room, topCells);
            Transfers.Plan fromBag = Transfers.take(room - fromWindow.moved(), storageCells(player.getInventory(), cursor));
            if (fromWindow.moved() + fromBag.moved() == 0) {
                return null;
            }
            for (Transfers.Move move : fromWindow.moves()) {
                InputSlots.Input input = live.get().inputs().at(move.slot()).orElseThrow();
                StackView before = BukkitStacks.view(top.getItem(move.slot()));
                int left = before.amount() - move.count();
                StackView after = new StackView(left > 0 ? before.id() : "", left, before.maxStack());
                if (events.slotChange(viewer, session, live.get(), input.layer(), move.slot(), before, after)) {
                    return null;
                }
            }
            for (Transfers.Move move : fromWindow.moves()) {
                ItemStack item = top.getItem(move.slot());
                top.setItem(move.slot(), BukkitStacks.withAmount(item, BukkitStacks.view(item).amount() - move.count()));
            }
            for (Transfers.Move move : fromBag.moves()) {
                ItemStack item = player.getInventory().getItem(move.slot());
                player.getInventory().setItem(move.slot(),
                        BukkitStacks.withAmount(item, BukkitStacks.view(item).amount() - move.count()));
            }
            player.setItemOnCursor(BukkitStacks.withAmount(cursor, cur.amount() + fromWindow.moved() + fromBag.moved()));
            journal.mirror(player);
            return null;
        });
    }

    /**
     * Whether a drag over the window may go ahead. The server places the items; each input slot the drag
     * touches is checked with the item it will hold, so a refused slot refuses the whole drag.
     */
    public boolean drag(GuiMenu menu, InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return false;
        }
        Optional<RuntimeGuis.Live> live = liveOf(menu, player);
        if (live.isEmpty()) {
            return false;
        }
        Viewer viewer = Viewer.of(player);
        ItemStack cursor = event.getOldCursor();
        int topSize = menu.getInventory().getSize();
        boolean allowed = during(player.getUniqueId(), () -> {
            for (int raw : event.getRawSlots()) {
                if (raw >= topSize) {
                    continue;
                }
                Optional<InputSlots.Input> input = live.get().inputs().at(raw);
                if (input.isEmpty()) {
                    return false;
                }
                StackView before = BukkitStacks.view(menu.getInventory().getItem(raw));
                StackView after = BukkitStacks.view(event.getNewItems().get(raw));
                int added = after.amount() - before.amount();
                if (added <= 0) {
                    continue;
                }
                StackView entering = BukkitStacks.view(cursor).withAmount(added);
                if (!events.accepts(viewer, menu.session(), input.get(), raw, entering, before)) {
                    return false;
                }
                if (events.slotChange(viewer, menu.session(), live.get(), input.get().layer(), raw, before, after)) {
                    return false;
                }
            }
            return true;
        });
        if (allowed) {
            journal.mirrorNextTick(player); // the server places the drag after this handler returns
        }
        return allowed;
    }

    // --- helpers ------------------------------------------------------------------

    private boolean allows(Viewer viewer, GuiSession session, RuntimeGuis.Live live, InputSlots.Input input,
            int slot, StackView before, SlotPolicy.Change change) {
        if (change.entering() != null && !events.accepts(viewer, session, input, slot, change.entering(), before)) {
            return false;
        }
        boolean changed = change.entering() != null || change.afterAmount() != before.amount();
        if (!changed) {
            return true;
        }
        var after = new StackView(change.afterId(), change.afterAmount(), before.maxStack());
        return !events.slotChange(viewer, session, live, input.layer(), slot, before, after);
    }

    /** The viewer's storage (main inventory, then hotbar) as cells for a transfer of {@code like}. */
    private static List<Transfers.Cell> storageCells(PlayerInventory storage, ItemStack like) {
        var cells = new ArrayList<Transfers.Cell>();
        for (int index = 9; index <= 35; index++) {
            cells.add(cellOf(storage, index, like));
        }
        for (int index = 0; index <= 8; index++) {
            cells.add(cellOf(storage, index, like));
        }
        return cells;
    }

    private static Transfers.Cell cellOf(PlayerInventory storage, int index, ItemStack like) {
        ItemStack item = storage.getItem(index);
        return new Transfers.Cell(index, BukkitStacks.view(item).amount(), BukkitStacks.similar(item, like));
    }

    /** {@code current} with {@code count} more of {@code like}: a copy, grown, or a new stack when it was empty. */
    private static ItemStack grown(ItemStack current, ItemStack like, int count) {
        StackView view = BukkitStacks.view(current);
        if (view.empty()) {
            return BukkitStacks.withAmount(like, count);
        }
        return BukkitStacks.withAmount(current, view.amount() + count);
    }

    private Optional<RuntimeGuis.Live> liveOf(GuiMenu menu, HumanEntity who) {
        GuiSession session = menu.session();
        if (!(who instanceof Player player) || !session.isOpen()
                || sessions.current(player.getUniqueId()).filter(current -> current == session).isEmpty()) {
            return Optional.empty();
        }
        return guis.find(session.gui());
    }

    /** Runs {@code work} with the player marked as mid-click, and returns what it returned. */
    private <T> T during(UUID player, Supplier<T> work) {
        AtomicReference<T> out = new AtomicReference<>();
        guard.during(player, () -> out.set(work.get()));
        return out.get();
    }
}
