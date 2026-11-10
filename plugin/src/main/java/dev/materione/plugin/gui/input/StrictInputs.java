package dev.materione.plugin.gui.input;

import dev.materione.plugin.escrow.runtime.BukkitLive;
import dev.materione.plugin.escrow.runtime.Delta;
import dev.materione.plugin.escrow.runtime.EscrowRuntime;
import dev.materione.plugin.escrow.runtime.Tier1;
import dev.materione.plugin.gui.GuiMenu;
import java.util.List;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The GUI side of the crash-safe input runtime (gui-input-durability.md §5.5; row P of #247): everything the click
 * rules, the window host, the slot actions and the close handler do for the slots of {@code durable: required}
 * layers goes through here, into the one {@link Tier1} API. A window's other input slots ({@code best_effort}) never
 * reach this class: they keep the journal and the server-applied clicks exactly as before.
 *
 * <p>A required slot is <em>escrowed</em>: {@link GuiMenu#durableSlots()}. It is served only while the player's
 * standing is {@link EscrowRuntime.Standing#SERVED}; otherwise every click that touches it is refused with the
 * actionbar line of §7, so a required layer renders but accepts nothing and is never quietly treated as best-effort.
 * {@link #NONE}, the instance of a host without the escrow runtime, therefore refuses required slots too.
 */
public final class StrictInputs implements EscrowRuntime.WindowView {

    /** No escrow runtime: required slots are unsupported, best-effort slots are untouched. */
    public static final StrictInputs NONE = new StrictInputs(null);

    private final EscrowRuntime runtime;

    public StrictInputs(EscrowRuntime runtime) {
        this.runtime = runtime;
    }

    /** Whether the window has any required slot. */
    public boolean has(GuiMenu menu) {
        return !menu.durableSlots().isEmpty();
    }

    /** Whether {@code slot} belongs to a required layer of the window. */
    public boolean escrowed(GuiMenu menu, int slot) {
        return menu.durableSlots().contains(slot);
    }

    /** How the player's required slots stand: served, or why they are refused. */
    public EscrowRuntime.Standing standing(Player player) {
        return runtime == null ? EscrowRuntime.Standing.UNSUPPORTED : runtime.standing(player);
    }

    /** Whether a click may touch {@code slot}: it is not a required slot, or required slots are served. */
    public boolean usable(Player player, GuiMenu menu, int slot) {
        return !escrowed(menu, slot) || standing(player) == EscrowRuntime.Standing.SERVED;
    }

    /** One refusal: the actionbar line, and the reason logged once a minute with its code (§7). */
    public void refuse(Player player, Tier1.Reason reason) {
        if (runtime != null) {
            runtime.refuse(player, reason);
        }
    }

    /**
     * Applies one manual click's whole result in one tier-1 step: the escrowed slots of {@code delta} through the
     * account, every other write as plain stacks. A refusal changes nothing, tells the player and returns false.
     */
    public boolean apply(Player player, GuiMenu menu, Delta<ItemStack> delta) {
        if (runtime == null) {
            if (delta.tops().keySet().stream().anyMatch(slot -> escrowed(menu, slot))) {
                return false;
            }
            plain(player, menu, delta);
            return true;
        }
        Tier1.Result<ItemStack> result = runtime.tier1().apply(runtime.of(player), live(player, menu),
                menu.guiId().toString(), slot -> escrowed(menu, slot), delta);
        if (result instanceof Tier1.Denied<ItemStack> denied) {
            runtime.refuse(player, denied.reason());
            return false;
        }
        return true;
    }

    private static void plain(Player player, GuiMenu menu, Delta<ItemStack> delta) {
        delta.tops().forEach(menu.getInventory()::setItem);
        delta.bags().forEach(player.getInventory()::setItem);
        if (delta.cursorSet()) {
            player.setItemOnCursor(delta.cursorStack());
        }
    }

    // ---- the slot actions (gui.slot_item) -------------------------------------------------------------------

    /** {@code take} on a required slot: the stack goes to the inventory or is owed. Empty on success, else the reason. */
    public Optional<String> take(Player player, GuiMenu menu, int slot) {
        return outcome(player, runtime == null ? null : runtime.tier1().take(runtime.of(player), live(player, menu),
                menu.guiId().toString(), slot));
    }

    /** {@code set_amount}, {@code repair} or {@code enchant} on a required slot: the entry becomes {@code edited}. */
    public Optional<String> edit(Player player, GuiMenu menu, int slot, ItemStack edited) {
        return outcome(player, runtime == null ? null : runtime.tier1().edit(runtime.of(player), live(player, menu),
                menu.guiId().toString(), slot, edited));
    }

    private Optional<String> outcome(Player player, Tier1.Result<ItemStack> result) {
        if (result == null) {
            return Optional.of("crash-safe input slots are not available on this server");
        }
        if (result instanceof Tier1.Denied<ItemStack> denied) {
            runtime.refuse(player, denied.reason());
            return Optional.of(denied.reason() == Tier1.Reason.BUSY
                    ? "the slot is busy"
                    : "the transfer could not be recorded");
        }
        Tier1.Done<ItemStack> done = (Tier1.Done<ItemStack>) result;
        runtime.owed(player, done.owed());
        return Optional.empty();
    }

    // ---- closing and moving windows --------------------------------------------------------------------------

    /**
     * A window closes (or its session ends): the escrowed slots' stacks go to the inventory, what does not fit is
     * owed, never dropped (§5.5). What the account could not hold is handed back the old way, so nothing is lost.
     */
    public void release(Player player, GuiMenu menu) {
        if (runtime == null || !has(menu)) {
            return;
        }
        Tier1.Result<ItemStack> result = runtime.tier1().release(runtime.of(player), live(player, menu),
                menu.guiId().toString(), menu.durableSlots());
        if (result instanceof Tier1.Done<ItemStack> done) {
            done.strays().forEach(stray -> InputReturns.giveBack(player, stray));
            runtime.owed(player, done.owed());
        }
    }

    /** A re-open: the old window's escrowed stacks are lifted out and stay HELD, between two windows. */
    public List<Tier1.Carried<ItemStack>> beginCarry(Player player, GuiMenu previous) {
        if (runtime == null || !has(previous)) {
            return List.of();
        }
        return runtime.tier1().beginCarry(runtime.of(player), live(player, previous), previous.durableSlots());
    }

    /** The new window is open: carried stacks go to the same slot when it is still escrowed, else to the player. */
    public void placeCarried(Player player, GuiMenu menu, List<Tier1.Carried<ItemStack>> carried) {
        if (runtime == null || carried.isEmpty()) {
            return;
        }
        Tier1.Result<ItemStack> result = runtime.tier1().placeCarried(runtime.of(player), live(player, menu), carried,
                menu.durableSlots()::contains);
        if (result instanceof Tier1.Done<ItemStack> done) {
            done.strays().forEach(stray -> InputReturns.giveBack(player, stray));
            runtime.owed(player, done.owed());
        }
    }

    /** The new window did not open: carried stacks return to the old window's slots. */
    public void restoreCarried(Player player, GuiMenu previous, List<Tier1.Carried<ItemStack>> carried) {
        if (runtime != null && !carried.isEmpty()) {
            runtime.tier1().restoreCarried(runtime.of(player), live(player, previous), carried);
        }
    }

    /** The carried stacks of a window that is gone: back to the player (the inventory, or owed). */
    public void returnCarried(Player player, List<Tier1.Carried<ItemStack>> carried) {
        if (runtime == null || carried.isEmpty()) {
            return;
        }
        Tier1.Result<ItemStack> result = runtime.tier1().placeCarried(runtime.of(player), new BukkitLive(player, null),
                carried, slot -> false);
        if (result instanceof Tier1.Done<ItemStack> done) {
            done.strays().forEach(stray -> InputReturns.giveBack(player, stray));
            runtime.owed(player, done.owed());
        }
    }

    // ---- the window the escrow runtime asks about --------------------------------------------------------------

    @Override
    public Optional<Open> open(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuiMenu menu && has(menu)) {
            return Optional.of(new Open(menu.guiId().toString(), menu.getInventory(), menu.durableSlots()));
        }
        return Optional.empty();
    }

    private static BukkitLive live(Player player, GuiMenu menu) {
        return new BukkitLive(player, menu.getInventory());
    }
}
