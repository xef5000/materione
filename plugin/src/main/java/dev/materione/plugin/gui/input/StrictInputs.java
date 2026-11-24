package dev.materione.plugin.gui.input;

import dev.materione.plugin.escrow.runtime.BukkitLive;
import dev.materione.api.custody.CustodyHolder;
import dev.materione.plugin.escrow.model.EntryState;
import dev.materione.plugin.escrow.runtime.CustodyAccount;
import dev.materione.plugin.escrow.runtime.CustodyConsume;
import dev.materione.plugin.escrow.runtime.Delta;
import dev.materione.plugin.escrow.runtime.EscrowRuntime;
import dev.materione.plugin.escrow.runtime.ItemStacks;
import dev.materione.plugin.escrow.runtime.CustodyTransfer;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.TransferHolder;
import dev.materione.plugin.escrow.runtime.Tier1;
import dev.materione.plugin.gui.GuiMenu;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
    private final InputHolders holders;

    public StrictInputs(EscrowRuntime runtime) {
        this.runtime = runtime;
        this.holders = new InputHolders(runtime);
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
        if (!escrowed(menu, slot)) {
            return true;
        }
        return holders.resolve(player, menu, slot).filter(InputHolders.Resolved::open).isPresent();
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
        var groups = holders.strictGroups(player, menu, delta, menu.durableSlots());
        if (groups.size() != 1) {
            refuse(player, Tier1.Reason.UNAVAILABLE);
            return false;
        }
        var entry = groups.entrySet().iterator().next();
        CustodyAccount account = entry.getKey();
        Tier1.Result<ItemStack> result = runtime.tier1().apply(account, live(player, menu),
                menu.guiId().toString(), entry.getValue()::contains, delta);
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
        Optional<InputHolders.Resolved> resolved = holders.resolve(player, menu, slot);
        return outcome(player, runtime == null || resolved.isEmpty() ? null : runtime.tier1().take(
                resolved.get().account(), live(player, menu),
                menu.guiId().toString(), slot));
    }

    /** {@code set_amount}, {@code repair} or {@code enchant} on a required slot: the entry becomes {@code edited}. */
    public Optional<String> edit(Player player, GuiMenu menu, int slot, ItemStack edited) {
        Optional<InputHolders.Resolved> resolved = holders.resolve(player, menu, slot);
        return outcome(player, runtime == null || resolved.isEmpty() ? null : runtime.tier1().edit(
                resolved.get().account(), live(player, menu),
                menu.guiId().toString(), slot, edited));
    }

    // ---- consuming what a required slot holds (#248: the tier-2 exit) ------------------------------------------

    /**
     * Consumes the stack held in a required slot, atomically, through the tier-2 exit ({@link CustodyConsume}); the
     * stage completes empty when the consume reached {@code reach}, or with the reason it did not. A refusal that
     * changed nothing (the slot is busy, the gate is shut) is told to the player here and completes at once; one that
     * could not record the decision is told when it happens. The caller is on the player's own thread.
     */
    public CompletionStage<Optional<String>> consume(Player player, GuiMenu menu, int slot, CustodyConsume.Reach reach) {
        if (!usable(player, menu, slot)) {
            refuse(player, Tier1.Reason.UNAVAILABLE);
            return CompletableFuture.completedFuture(Optional.of(UNAVAILABLE));
        }
        Optional<InputHolders.Resolved> resolved = holders.resolve(player, menu, slot);
        if (resolved.isEmpty() || resolved.get().holder() instanceof CustodyHolder.Stack) {
            return CompletableFuture.completedFuture(Optional.of(
                    "custody exits are unsupported for a stack holder"));
        }
        Optional<CustodyConsume.Started<ItemStack>> started = runtime == null ? Optional.empty()
                : runtime.consume(player, resolved.get().holder(), menu.guiId().toString(), slot, reach);
        if (started.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.of(UNAVAILABLE));
        }
        if (started.get() instanceof CustodyConsume.Declined<ItemStack> declined) {
            return CompletableFuture.completedFuture(Optional.of(reasonText(declined.reason())));
        }
        return ((CustodyConsume.Running<ItemStack>) started.get()).outcome().thenApply(StrictInputs::reasonOf);
    }

    /** Resolves a slot into an exact source and starts the one-joint-decision transfer. */
    public CompletionStage<Optional<String>> transfer(Player player, GuiMenu menu, InputSlotPort.Transfer transfer) {
        if (runtime == null) {
            return CompletableFuture.completedFuture(Optional.of("custody transfer is unavailable"));
        }
        TransferHolder sourceHolder = transfer.sourceHolder();
        Id128 sourceEntry = transfer.sourceEntry();
        if (transfer.sourceSlot() != null) {
            int slot = transfer.sourceSlot();
            Optional<InputHolders.Resolved> source = holders.resolve(player, menu, slot);
            if (source.isEmpty() || !source.get().open()) {
                return CompletableFuture.completedFuture(Optional.of("the source slot is not held in custody"));
            }
            try {
                CustodyHolder held = source.get().holder();
                Id128 owner = new Id128(held.owner().getMostSignificantBits(), held.owner().getLeastSignificantBits());
                sourceHolder = switch (held) {
                    case CustodyHolder.Player ignored -> new TransferHolder(owner, TransferHolder.Kind.PLAYER, "");
                    case CustodyHolder.Keyed keyed -> new TransferHolder(owner, TransferHolder.Kind.KEYED, keyed.key());
                    case CustodyHolder.Stack ignored -> throw new IllegalArgumentException(
                            "stack PDC cannot acknowledge custody.transfer");
                };
            } catch (IllegalArgumentException unsupported) {
                return CompletableFuture.completedFuture(Optional.of(unsupported.getMessage()));
            }
            var entry = Tier1.entryAt(source.get().account().account(), slot);
            if (entry == null) {
                return CompletableFuture.completedFuture(Optional.of("the source slot is stale or empty"));
            }
            sourceEntry = entry.id();
        }
        if (sourceHolder == null || sourceEntry == null || sourceEntry.isZero()
                || transfer.destinationHolder() == null) {
            return CompletableFuture.completedFuture(Optional.of("transfer source and destination are incomplete"));
        }
        Id128 transaction = transfer.transaction();
        if (transaction == null) {
            var id = java.util.UUID.randomUUID();
            transaction = new Id128(id.getMostSignificantBits(), id.getLeastSignificantBits());
        }
        if (transaction.isZero()) {
            return CompletableFuture.completedFuture(Optional.of("transfer id cannot be zero"));
        }
        Optional<CompletionStage<CustodyTransfer.Outcome>> started = runtime.transfer(new CustodyTransfer.Request(
                transaction, sourceHolder, sourceEntry, transfer.destinationHolder(),
                transfer.destinationSlot(), transfer.reach()));
        if (started.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.of("custody transfer is unavailable"));
        }
        return started.get().thenApply(result -> switch (result) {
            case CustodyTransfer.Outcome.Transferred transferred -> Optional.empty();
            case CustodyTransfer.Outcome.Refused refused -> Optional.of(refused.reason());
        });
    }

    /**
     * {@code gui.slot_item clear} (and {@code set_amount 0}) on a required slot: a consume that nothing waits for. Empty
     * when the exit began (its decision is recorded off-thread; a later refusal is told to the player), else the reason
     * it could not begin.
     */
    public Optional<String> clear(Player player, GuiMenu menu, int slot) {
        CompletionStage<Optional<String>> stage = consume(player, menu, slot, CustodyConsume.Reach.DECIDED);
        CompletableFuture<Optional<String>> now = stage.toCompletableFuture();
        // a step-1 refusal has already completed the stage; a running exit has not (never waited for: nothing blocks here)
        return now.isDone() ? now.getNow(Optional.empty()) : Optional.empty();
    }

    private static final String UNAVAILABLE = "crash-safe input slots are not available for this player right now";

    private static String reasonText(Tier1.Reason reason) {
        return switch (reason) {
            case BUSY -> "the slot is busy";
            case UNAVAILABLE -> UNAVAILABLE;
            case UNRECORDABLE -> "the transfer could not be recorded";
        };
    }

    private static Optional<String> reasonOf(CustodyConsume.Outcome outcome) {
        return switch (outcome) {
            case CustodyConsume.Consumed consumed -> Optional.empty();
            case CustodyConsume.Refused refused -> Optional.of(reasonText(refused.reason()));
            case CustodyConsume.Abandoned abandoned -> Optional.of("the consume is decided, but its save was not confirmed ("
                    + switch (abandoned.why()) {
                        case PLAYER_LEFT -> "the player left";
                        case TIMED_OUT -> "no saved snapshot was proven in time";
                        case SHUTDOWN -> "the plugin is stopping";
                    } + "), so what waited for it did not run");
        };
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
        var groups = holders.slotsByHolder(player, menu, menu.durableSlots());
        for (var group : groups.entrySet()) {
            Optional<CustodyAccount> account = runtime.account(player, group.getKey());
            if (account.isEmpty() || !account.get().open()) {
                continue;
            }
            if (group.getKey() instanceof CustodyHolder.Player) {
                Tier1.Result<ItemStack> result = runtime.tier1().release(account.get(), live(player, menu),
                        menu.guiId().toString(), group.getValue());
                if (result instanceof Tier1.Done<ItemStack> done) {
                    done.strays().forEach(stray -> InputReturns.giveBack(player, stray));
                    runtime.owed(player, done.owed());
                }
            } else {
                for (int slot : group.getValue()) {
                    menu.getInventory().setItem(slot, null);
                }
            }
        }
    }

    /** A re-open: the old window's escrowed stacks are lifted out and stay HELD, between two windows. */
    public List<Tier1.Carried<ItemStack>> beginCarry(Player player, GuiMenu previous) {
        if (runtime == null || !has(previous)) {
            return List.of();
        }
        var slots = holders.playerSlots(player, previous);
        return runtime.tier1().beginCarry(runtime.of(player), live(player, previous), slots);
    }

    /** The new window is open: carried stacks go to the same slot when it is still escrowed, else to the player. */
    public void placeCarried(Player player, GuiMenu menu, List<Tier1.Carried<ItemStack>> carried) {
        if (runtime == null || carried.isEmpty()) {
            return;
        }
        Tier1.Result<ItemStack> result = runtime.tier1().placeCarried(runtime.of(player), live(player, menu), carried,
                slot -> menu.durableSlots().contains(slot)
                        && holders.holderOf(player, menu, slot) instanceof CustodyHolder.Player);
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

    /** Restores parked HELD entries of keyed/stack holders into an opened window. */
    public void restoreParked(Player player, GuiMenu menu) {
        if (runtime == null) {
            return;
        }
        for (int slot : menu.durableSlots()) {
            Optional<InputHolders.Resolved> resolved = holders.resolve(player, menu, slot);
            if (resolved.isEmpty() || resolved.get().holder() instanceof CustodyHolder.Player
                    || !resolved.get().open()) {
                continue;
            }
            resolved.get().account().account().entries().stream()
                    .filter(entry -> entry.state() == EntryState.HELD && entry.slotHint() == slot)
                    .findFirst()
                    .ifPresent(entry -> menu.getInventory().setItem(slot, ItemStacks.INSTANCE.decode(entry.stack())));
        }
    }

    private static BukkitLive live(Player player, GuiMenu menu) {
        return new BukkitLive(player, menu.getInventory());
    }
}
