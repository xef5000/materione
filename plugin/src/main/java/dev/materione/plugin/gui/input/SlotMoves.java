package dev.materione.plugin.gui.input;

import dev.materione.plugin.escrow.runtime.Delta;
import dev.materione.plugin.gui.GuiMenu;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The three moves that span several slots (logic.md §25): a shift-click into the input slots, a shift-click out of
 * one, and a double-click collection. Each is planned ({@link Transfers}), checked slot by slot, and applied all or
 * nothing as one {@link Delta}: through {@link StrictInputs#apply}, which puts the escrowed slots through the account
 * in the same step and writes the rest as plain stacks. Split from {@link InputSlotClicks}, which delegates here.
 */
final class SlotMoves {

    private final GuiSessions sessions;
    private final GuiEvents events;
    private final InputGuard guard;
    private final GuiJournal journal;
    private final StrictInputs strict;

    SlotMoves(GuiSessions sessions, GuiEvents events, InputGuard guard, GuiJournal journal, StrictInputs strict) {
        this.sessions = sessions;
        this.events = events;
        this.guard = guard;
        this.journal = journal;
        this.strict = strict;
    }

    private static InputSlots inputs(GuiMenu menu, RuntimeGuis.Live gui) {
        return gui.inputs().only(menu.inputSlots());
    }

    /**
     * A shift-click from the viewer's inventory into the window's input slots: the item goes into the
     * input slots that accept it, similar stacks first, then empty ones, in slot order.
     */
    void shiftIn(GuiMenu menu, InventoryClickEvent event) {
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
            boolean unserved = false;
            for (InputSlots.Input input : inputs(menu, live.get()).all()) {
                if (!strict.usable(player, menu, input.slot())) {
                    unserved = true; // a required slot on a server that cannot serve it accepts nothing
                    continue;
                }
                ItemStack item = top.getItem(input.slot());
                if (events.accepts(viewer, session, input, input.slot(), src, BukkitStacks.view(item))) {
                    cells.add(new Transfers.Cell(input.slot(), BukkitStacks.view(item).amount(),
                            BukkitStacks.similar(item, source)));
                }
            }
            Transfers.Plan plan = Transfers.put(src.amount(), src.maxStack(), cells);
            if (plan.moved() == 0) {
                if (unserved) {
                    strict.refuse(player, dev.materione.plugin.escrow.runtime.Tier1.Reason.UNAVAILABLE);
                }
                return null;
            }
            for (Transfers.Move move : plan.moves()) {
                InputSlots.Input input = inputs(menu, live.get()).at(move.slot()).orElseThrow();
                StackView before = BukkitStacks.view(top.getItem(move.slot()));
                StackView after = before.empty() ? src.withAmount(move.count())
                        : before.withAmount(before.amount() + move.count());
                if (events.slotChange(viewer, session, live.get(), input.layer(), move.slot(), before, after)) {
                    return null; // all or nothing: a refused slot refuses the whole shift
                }
            }
            Delta<ItemStack> delta = new Delta<>();
            for (Transfers.Move move : plan.moves()) {
                delta.top(move.slot(), grown(top.getItem(move.slot()), source, move.count()));
            }
            delta.bag(event.getSlot(), BukkitStacks.withAmount(source, src.amount() - plan.moved()));
            if (strict.apply(player, menu, delta)) {
                journal.mirror(player);
            }
            return null;
        });
    }

    /**
     * A shift-click from an input slot into the viewer's inventory: the item goes into the viewer's storage,
     * similar stacks first, then empty slots (main inventory, then hotbar).
     */
    void shiftOut(GuiMenu menu, InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Optional<RuntimeGuis.Live> live = liveOf(menu, player);
        int slot = event.getRawSlot();
        Optional<InputSlots.Input> input = live.flatMap(view -> inputs(menu, view).at(slot));
        ItemStack item = menu.getInventory().getItem(slot);
        StackView before = BukkitStacks.view(item);
        if (live.isEmpty() || input.isEmpty() || before.empty()) {
            return;
        }
        if (!strict.usable(player, menu, slot)) {
            strict.refuse(player, dev.materione.plugin.escrow.runtime.Tier1.Reason.UNAVAILABLE);
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
            Delta<ItemStack> delta = new Delta<>();
            for (Transfers.Move move : plan.moves()) {
                delta.bag(move.slot(), grown(storage.getItem(move.slot()), item, move.count()));
            }
            delta.top(slot, BukkitStacks.withAmount(item, left));
            if (strict.apply(player, menu, delta)) {
                journal.mirror(player);
            }
            return null;
        });
    }

    /**
     * A double-click collection onto the cursor: matching items come from the input slots first, then from
     * the viewer's inventory, up to a full stack. Display slots of the window are never a source.
     */
    void collect(GuiMenu menu, InventoryClickEvent event) {
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
            for (InputSlots.Input input : inputs(menu, live.get()).all()) {
                if (!strict.usable(player, menu, input.slot())) {
                    continue; // a required slot on a server that cannot serve it gives nothing up
                }
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
                InputSlots.Input input = inputs(menu, live.get()).at(move.slot()).orElseThrow();
                StackView before = BukkitStacks.view(top.getItem(move.slot()));
                int left = before.amount() - move.count();
                StackView after = new StackView(left > 0 ? before.id() : "", left, before.maxStack());
                if (events.slotChange(viewer, session, live.get(), input.layer(), move.slot(), before, after)) {
                    return null;
                }
            }
            Delta<ItemStack> delta = new Delta<>();
            for (Transfers.Move move : fromWindow.moves()) {
                ItemStack item = top.getItem(move.slot());
                delta.top(move.slot(), BukkitStacks.withAmount(item, BukkitStacks.view(item).amount() - move.count()));
            }
            for (Transfers.Move move : fromBag.moves()) {
                ItemStack item = player.getInventory().getItem(move.slot());
                delta.bag(move.slot(), BukkitStacks.withAmount(item, BukkitStacks.view(item).amount() - move.count()));
            }
            delta.cursor(BukkitStacks.withAmount(cursor, cur.amount() + fromWindow.moved() + fromBag.moved()));
            if (strict.apply(player, menu, delta)) {
                journal.mirror(player);
            }
            return null;
        });
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
        if (!(who instanceof Player player) || !session.isOpen() || session.window() != menu
                || sessions.current(player.getUniqueId()).filter(current -> current == session).isEmpty()) {
            return Optional.empty();
        }
        return session.displayed();
    }

    /** Runs {@code work} with the player marked as mid-click, and returns what it returned. */
    private <T> T during(UUID player, Supplier<T> work) {
        AtomicReference<T> out = new AtomicReference<>();
        guard.during(player, () -> out.set(work.get()));
        return out.get();
    }
}
