package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.PluginGui;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.session.GuiSessions;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

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

    private final GuiSessions sessions;
    private final GuiEvents events;
    private final InputGuard guard;
    private final GuiJournal journal;
    private final StrictInputs strict;
    private final ManualClicks manual;
    private final SlotMoves moves;

    public InputSlotClicks(GuiLookup guis, GuiSessions sessions, GuiEvents events, InputGuard guard,
            GuiJournal journal) {
        this(guis, sessions, events, guard, journal, StrictInputs.NONE);
    }

    /** @param strict the escrow path of {@code durable: required} slots (gui-input-durability.md, row P of #247) */
    public InputSlotClicks(GuiLookup guis, GuiSessions sessions, GuiEvents events, InputGuard guard,
            GuiJournal journal, StrictInputs strict) {
        this.sessions = sessions;
        this.events = events;
        this.guard = guard;
        this.journal = journal;
        this.strict = strict;
        this.manual = new ManualClicks(strict);
        this.moves = new SlotMoves(sessions, events, guard, journal, strict);
    }

    /** What the listener does with a single-slot click or a drag on input slots. */
    public enum Verdict {
        /** Refused: cancel it. */
        DENY,
        /** Allowed, and the server applies it (best-effort slots only; the journal mirrors it a tick later). */
        SERVER,
        /** Allowed, and the plugin applies it itself in one step ({@code durable: required} slots). */
        MANUAL
    }

    /** The policy of the presentation currently displayed to the viewer. */
    public WindowPolicy policyOf(PluginGui gui, HumanEntity who) {
        if (!(gui instanceof GuiMenu menu)) {
            return WindowPolicy.LEGACY;
        }
        return liveOf(menu, who).map(live -> WindowPolicy.live(inputs(menu, live))).orElse(WindowPolicy.STALE);
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
     * Whether a single-slot click on an input slot may go ahead, and who applies it. {@link Verdict#SERVER}: the
     * server applies it after this returns (best-effort slots); {@link Verdict#MANUAL}: the listener cancels it and
     * {@link #applyManual} applies it (required slots); {@link Verdict#DENY}: cancelled, and nothing changes.
     */
    public Verdict checked(GuiMenu menu, InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return Verdict.DENY;
        }
        Optional<RuntimeGuis.Live> live = liveOf(menu, player);
        int slot = event.getRawSlot();
        Optional<InputSlots.Input> input = live.flatMap(view -> inputs(menu, view).at(slot));
        if (live.isEmpty() || input.isEmpty()) {
            return Verdict.DENY;
        }
        if (!strict.usable(player, menu, slot)) {
            strict.refuse(player, dev.materione.plugin.escrow.runtime.Tier1.Reason.UNAVAILABLE);
            return Verdict.DENY;
        }
        int button = event.getHotbarButton();
        ItemStack swap = button == SlotPolicy.OFFHAND_BUTTON
                ? player.getInventory().getItemInOffHand()
                : button >= 0 && button <= 8 ? player.getInventory().getItem(button) : null;
        StackView before = BukkitStacks.view(menu.getInventory().getItem(slot));
        SlotPolicy.Change change = SlotPolicy.change(event.getAction(), button, before,
                BukkitStacks.view(event.getCursor()), BukkitStacks.view(swap));
        if (change == null) {
            return Verdict.DENY;
        }
        Viewer viewer = Viewer.of(player);
        boolean allowed = during(player.getUniqueId(), () ->
                allows(viewer, menu.session(), live.get(), input.get(), slot, before, change));
        if (!allowed) {
            return Verdict.DENY;
        }
        if (strict.escrowed(menu, slot)) {
            return Verdict.MANUAL;
        }
        journal.mirrorNextTick(player); // the server applies the click after this handler returns
        return Verdict.SERVER;
    }

    /**
     * Applies a click {@link #checked} answered {@link Verdict#MANUAL} for, in one tier-1 step with the account (the
     * event is cancelled; the gui's own rules have seen it). The window is read afresh: a rule that ran in between may
     * have changed it, and a window that is no longer the player's live one is left alone.
     */
    public void applyManual(GuiMenu menu, InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && liveOf(menu, player).isPresent()
                && strict.escrowed(menu, event.getRawSlot())) {
            during(player.getUniqueId(), () -> {
                manual.click(player, menu, event);
                return null;
            });
        }
    }

    /**
     * A shift-click from the viewer's inventory into the window's input slots: the item goes into the
     * input slots that accept it, similar stacks first, then empty ones, in slot order.
     */
    public void shiftIn(GuiMenu menu, InventoryClickEvent event) {
        moves.shiftIn(menu, event);
    }

    /**
     * A shift-click from an input slot into the viewer's inventory: the item goes into the viewer's storage,
     * similar stacks first, then empty slots (main inventory, then hotbar).
     */
    public void shiftOut(GuiMenu menu, InventoryClickEvent event) {
        moves.shiftOut(menu, event);
    }

    /**
     * A double-click collection onto the cursor: matching items come from the input slots first, then from
     * the viewer's inventory, up to a full stack. Display slots of the window are never a source.
     */
    public void collect(GuiMenu menu, InventoryClickEvent event) {
        moves.collect(menu, event);
    }

    /**
     * Whether a drag over the window may go ahead. The server places the items; each input slot the drag
     * touches is checked with the item it will hold, so a refused slot refuses the whole drag.
     */
    public Verdict drag(GuiMenu menu, InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return Verdict.DENY;
        }
        Optional<RuntimeGuis.Live> live = liveOf(menu, player);
        if (live.isEmpty()) {
            return Verdict.DENY;
        }
        boolean escrowed = false;
        for (int raw : event.getRawSlots()) {
            if (raw < menu.getInventory().getSize() && strict.escrowed(menu, raw)) {
                if (!strict.usable(player, menu, raw)) {
                    strict.refuse(player, dev.materione.plugin.escrow.runtime.Tier1.Reason.UNAVAILABLE);
                    return Verdict.DENY;
                }
                escrowed = true;
            }
        }
        Viewer viewer = Viewer.of(player);
        ItemStack cursor = event.getOldCursor();
        int topSize = menu.getInventory().getSize();
        boolean allowed = during(player.getUniqueId(), () -> {
            for (int raw : event.getRawSlots()) {
                if (raw >= topSize) {
                    continue;
                }
                Optional<InputSlots.Input> input = inputs(menu, live.get()).at(raw);
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
        if (!allowed) {
            return Verdict.DENY;
        }
        if (escrowed) {
            return Verdict.MANUAL;
        }
        journal.mirrorNextTick(player); // the server places the drag after this handler returns
        return Verdict.SERVER;
    }

    /** Applies a drag {@link #drag} answered {@link Verdict#MANUAL} for, in one tier-1 step (the event is cancelled). */
    public void applyDrag(GuiMenu menu, InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && liveOf(menu, player).isPresent()) {
            during(player.getUniqueId(), () -> {
                manual.drag(player, menu, event);
                return null;
            });
        }
    }

    // --- helpers ------------------------------------------------------------------

    private static InputSlots inputs(GuiMenu menu, RuntimeGuis.Live gui) {
        return gui.inputs().only(menu.inputSlots());
    }

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
