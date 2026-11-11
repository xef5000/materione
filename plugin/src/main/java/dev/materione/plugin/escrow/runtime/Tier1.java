package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.gate.CrashGate;
import dev.materione.plugin.escrow.io.LedgerState;
import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.AccountRead;
import dev.materione.plugin.escrow.model.Entry;
import dev.materione.plugin.escrow.model.EntryState;
import dev.materione.plugin.escrow.model.EscrowTransfer;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.Recovery;
import dev.materione.plugin.escrow.model.Refusal;
import dev.materione.plugin.escrow.model.StackBytes;
import dev.materione.plugin.escrow.model.Step;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The one tier-1 API (gui-input-durability.md §5.4, §5.5, invariant I1): every change of who owns a stack that sits in
 * a window's escrowed slots is one call here, taking <em>both</em> sides, the {@link PlayerEscrow account} and the
 * {@link Live live state}, and performing them in one owner-thread step (no scheduler hop, no event, no IO between the
 * two). The account is encoded eagerly inside the step and written into the player's persistent data by
 * {@link PlayerEscrow#commit}, so the live persistent value always matches the live inventory and no save can see one
 * without the other (F3, F5, F6).
 *
 * <p>Every method follows one order: <em>plan</em> on the account alone (a refusal leaves both sides untouched, I7),
 * then write the persistent value, then change the live stacks. Where the model's step touches the inventory itself
 * ({@code close}, {@code moveOut}, {@code claim}, {@code death}), it can only shrink or keep the account, so nothing
 * is left that can fail after it.
 *
 * <p>The slot a window shows is what the player sees, so it is also the truth the account is brought back to when the
 * two disagree (another plugin changed a stack under us): a stack present in a slot without an entry is adopted, an
 * entry for an empty slot is dropped before the click. A release never loses a stack either way: it returns what the
 * slot holds and what the account owes.
 *
 * <p>Pure: generic over the stack ({@link Stacks}) and the live state ({@link Live}); the ids and the clock are
 * injected. Nothing here touches Bukkit, a file or a thread, which is what the runtime ban tests hold.
 *
 * @param <T> one stack
 */
public final class Tier1<T> {

    /** Why a strict step did nothing (§7). */
    public enum Reason {
        /** The player's gate is not open, or the platform lacks checkpoint support: the slots accept nothing. */
        UNAVAILABLE,
        /** The slot's entry is frozen (EXITING): a consume decision is in flight. */
        BUSY,
        /** The step could not be recorded: an account over its cap, or a stack that does not serialise ({@code MTR-0102}). */
        UNRECORDABLE
    }

    /** The outcome of a step. */
    public sealed interface Result<T> permits Done, Denied {}

    /**
     * The step happened.
     *
     * @param returned stacks put into the player's inventory
     * @param owed     stacks that are OWED after the step (including older ones that still do not fit)
     * @param strays   stacks the account could not hold (a desync that cannot be recorded): the caller returns them
     *                 the old way, never losing them
     */
    public record Done<T>(int returned, int owed, List<T> strays) implements Result<T> {

        public Done {
            strays = List.copyOf(strays);
        }
    }

    /**
     * The step was refused and changed nothing (I7).
     *
     * @param cause the model's refusal when there is one, else null
     */
    public record Denied<T>(Reason reason, Refusal cause) implements Result<T> {}

    /** A stack held between two windows by a re-open (see {@link #beginCarry}). */
    public record Carried<T>(int slot, T stack, Id128 entry) {}

    /** What a death did. */
    public record Death<T>(List<T> drops, int returned, int owed, int consumed) {

        public Death {
            drops = List.copyOf(drops);
        }
    }

    /** What recovery did on join. */
    public sealed interface Recovered permits Opened, Blocked {}

    /**
     * Recovery completed and the gate is open.
     *
     * @param returned stacks put back in the inventory
     * @param owed     stacks still OWED (the inventory was full)
     * @param dropped  entries whose consume decision was durable (G2)
     * @param adopted  legacy {@code gui_inputs} stacks adopted as OWED entries
     * @param legacyKept true when the legacy journal could not be adopted (the account was at its cap) and must stay
     */
    public record Opened(int returned, int owed, int dropped, int adopted, boolean legacyKept) implements Recovered {}

    /** The player's escrow state is unreadable: the gate stays shut, nothing was touched. */
    public record Blocked(Recovery.Cause cause, String detail) implements Recovered {}

    private Done<T> nothing() {
        return new Done<>(0, 0, List.of());
    }

    private final Stacks<T> stacks;
    private final Supplier<Id128> ids;
    private final LongSupplier clock;

    public Tier1(Stacks<T> stacks, Supplier<Id128> ids, LongSupplier clock) {
        this.stacks = Objects.requireNonNull(stacks, "stacks");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- a manual click --------------------------------------------------------------------------------------

    /**
     * Applies one click's whole result: the window slots, the inventory slots and the cursor of {@code delta}. Every
     * written window slot that is escrowed ({@code escrowed}) has its entry brought to what it ends holding, in the
     * same step. A refusal leaves everything as it was.
     */
    public Result<T> apply(PlayerEscrow escrow, Live<T> live, String gui, IntPredicate escrowed, Delta<T> delta) {
        boolean strict = delta.tops().keySet().stream().anyMatch(escrowed::test);
        if (strict) {
            if (!escrow.open()) {
                return new Denied<>(Reason.UNAVAILABLE, null);
            }
            Work work = new Work(escrow.account());
            for (var write : delta.tops().entrySet()) {
                if (escrowed.test(write.getKey())
                        && !transfer(work, live, gui, write.getKey(), write.getValue())) {
                    return work.denied;
                }
            }
            if (work.bytes != null) {
                escrow.commit(work.account, work.bytes);
            }
        }
        write(live, delta);
        if (strict) {
            CrashGate.at(CrashGate.Gates.CLICK_APPLIED); // test-only, inert in production
        }
        return nothing();
    }

    /** Brings the entry of {@code slot} from what it holds now to {@code after}. */
    private boolean transfer(Work work, Live<T> live, String gui, int slot, T after) {
        T before = live.top(slot);
        if (!sync(work, live, gui, slot, true)) {
            return false;
        }
        Entry entry = entryAt(work.account, slot);
        if (stacks.empty(after)) {
            return entry == null || work.run(EscrowTransfer.pickUp(work.account, entry.id()));
        }
        StackBytes bytes = encode(work, after);
        if (bytes == null) {
            return false;
        }
        if (entry != null && entry.stack().equals(bytes)) {
            return true;
        }
        if (entry != null && stacks.similar(before, after)) {
            return work.run(EscrowTransfer.editStack(work.account, entry.id(), bytes));
        }
        if (entry != null && !work.run(EscrowTransfer.pickUp(work.account, entry.id()))) {
            return false;
        }
        return work.run(EscrowTransfer.place(work.account, held(bytes, gui, slot)));
    }

    private void write(Live<T> live, Delta<T> delta) {
        delta.tops().forEach(live::top);
        delta.bags().forEach(live::bag);
        if (delta.cursorSet()) {
            live.cursor(delta.cursorStack());
        }
    }

    // ---- releases ---------------------------------------------------------------------------------------------

    /**
     * A window closes (player, flow, replaced window, quit): every HELD entry goes to the inventory and what does not
     * fit is OWED (§5.5); OWED entries try again first. Entries between two windows ({@link #beginCarry}) stay. What the
     * escrowed slots physically hold is cleared; a stack there the account does not know is adopted first, and if it
     * cannot be adopted it is handed back in {@link Done#strays()}.
     */
    public Result<T> release(PlayerEscrow escrow, Live<T> live, String gui, Collection<Integer> escrowedSlots) {
        if (!escrow.open()) {
            return nothing();
        }
        Work work = new Work(escrow.account());
        List<T> strays = adoptStrays(work, live, gui, escrowedSlots);
        BagInventory<T> bag = new BagInventory<>(live, stacks);
        if (escrow.carrying().isEmpty()) {
            work.run(EscrowTransfer.close(work.account, bag));
        } else {
            for (EntryState pass : new EntryState[] {EntryState.OWED, EntryState.HELD}) {
                for (Entry entry : List.copyOf(work.account.entries())) {
                    if (entry.state() == pass && !escrow.carrying().contains(entry.id())) {
                        work.run(EscrowTransfer.moveOut(work.account, bag, entry.id()));
                    }
                }
            }
        }
        finish(escrow, work);
        clear(live, escrowedSlots);
        CrashGate.at(CrashGate.Gates.RELEASE_DONE); // test-only, inert in production
        return new Done<>(work.returned, countOwed(work.account), strays);
    }

    /**
     * {@code gui.slot_item take}: the slot's stack goes to the inventory, or is OWED when it does not fit (never a
     * world drop, G4), and the slot empties.
     */
    public Result<T> take(PlayerEscrow escrow, Live<T> live, String gui, int slot) {
        if (!escrow.open()) {
            return new Denied<>(Reason.UNAVAILABLE, null);
        }
        Work work = new Work(escrow.account());
        if (!sync(work, live, gui, slot, true)) {
            return work.denied;
        }
        Entry entry = entryAt(work.account, slot);
        if (entry == null) {
            return nothing();
        }
        if (!work.run(EscrowTransfer.moveOut(work.account, new BagInventory<>(live, stacks), entry.id()))) {
            return work.denied;
        }
        finish(escrow, work);
        live.top(slot, null);
        return new Done<>(work.returned, countOwed(work.account), List.of());
    }

    /** {@code gui.slot_item set_amount / repair / enchant}: the slot's entry becomes {@code edited}, which the slot then holds. */
    public Result<T> edit(PlayerEscrow escrow, Live<T> live, String gui, int slot, T edited) {
        if (!escrow.open()) {
            return new Denied<>(Reason.UNAVAILABLE, null);
        }
        Delta<T> delta = new Delta<T>().top(slot, edited);
        return apply(escrow, live, gui, s -> s == slot, delta);
    }

    /** {@code /materione claim}: OWED entries try the inventory again (§5.5). */
    public Result<T> claim(PlayerEscrow escrow, Live<T> live) {
        if (!escrow.open()) {
            return new Denied<>(Reason.UNAVAILABLE, null);
        }
        Work work = new Work(escrow.account());
        work.run(EscrowTransfer.claim(work.account, new BagInventory<>(live, stacks)));
        finish(escrow, work);
        return new Done<>(work.returned, countOwed(work.account), List.of());
    }

    // ---- death ------------------------------------------------------------------------------------------------

    /**
     * Death, inside {@code PlayerDeathEvent} (§5.5, owner answer 10): one step, before the drop list is fixed. With
     * {@code keepInventory} the HELD entries return to the inventory (what does not fit is OWED); otherwise their
     * stacks are in {@link Death#drops()} for the caller to append to the event's list. The escrowed slots are cleared,
     * so the close the server does right after finds nothing to return a second time.
     */
    public Death<T> death(PlayerEscrow escrow, Live<T> live, String gui, Collection<Integer> escrowedSlots,
            Ledger ledger, boolean keepInventory) {
        if (!escrow.open()) {
            return new Death<>(List.of(), 0, 0, 0);
        }
        Work work = new Work(escrow.account());
        List<T> strays = adoptStrays(work, live, gui, escrowedSlots);
        BagInventory<T> bag = new BagInventory<>(live, stacks);
        List<T> drops = new ArrayList<>();
        int returned = 0;
        int consumed = 0;
        if (work.run(EscrowTransfer.death(work.account, ledger, bag, keepInventory))
                && work.last instanceof Step.Applied applied) {
            returned = applied.returned().size();
            consumed = applied.consumed().size();
            for (StackBytes released : applied.released()) {
                drops.add(stacks.decode(released));
            }
        }
        finish(escrow, work);
        clear(live, escrowedSlots);
        for (T stack : strays) {
            if (keepInventory && new BagInventory<>(live, stacks).tryAdd(stacks.encode(stack))) {
                returned++;
            } else {
                drops.add(stack);
            }
        }
        return new Death<>(drops, returned, countOwed(work.account), consumed);
    }

    // ---- a re-open --------------------------------------------------------------------------------------------

    /**
     * A re-open moves a window's stacks to the next window: the escrowed slots are emptied, and their entries stay HELD
     * in the account, marked as between windows so the close of the old window does not return them. {@link
     * #placeCarried} or {@link #restoreCarried} ends the carry in the same step. A slot whose stack the account does
     * not hold is left where it is: the old window's close returns it.
     */
    public List<Carried<T>> beginCarry(PlayerEscrow escrow, Live<T> live, Collection<Integer> escrowedSlots) {
        List<Carried<T>> carried = new ArrayList<>();
        if (!escrow.open()) {
            return carried;
        }
        for (int slot : new TreeSet<>(escrowedSlots)) {
            T content = live.top(slot);
            Entry entry = entryAt(escrow.account(), slot);
            if (stacks.empty(content) || entry == null || entry.state() != EntryState.HELD
                    || !matches(entry, content)) {
                continue;
            }
            carried.add(new Carried<>(slot, content, entry.id()));
            escrow.carrying().add(entry.id());
            live.top(slot, null);
        }
        return carried;
    }

    /**
     * The new window is open: each carried stack goes to the same slot when it is still an escrowed slot, otherwise
     * back to the player (the inventory, or OWED), never dropped.
     */
    public Result<T> placeCarried(PlayerEscrow escrow, Live<T> live, List<Carried<T>> carried, IntPredicate escrowed) {
        Work work = new Work(escrow.account());
        BagInventory<T> bag = new BagInventory<>(live, stacks);
        List<T> strays = new ArrayList<>();
        for (Carried<T> item : carried) {
            escrow.carrying().remove(item.entry());
            if (escrowed.test(item.slot()) && escrow.open()) {
                live.top(item.slot(), item.stack());
            } else if (!work.run(EscrowTransfer.moveOut(work.account, bag, item.entry()))) {
                strays.add(item.stack());
            }
        }
        finish(escrow, work);
        return new Done<>(work.returned, countOwed(work.account), strays);
    }

    /** The new window did not open: the carried stacks go back to the old window's slots, which are still its. */
    public void restoreCarried(PlayerEscrow escrow, Live<T> oldWindow, List<Carried<T>> carried) {
        for (Carried<T> item : carried) {
            escrow.carrying().remove(item.entry());
            oldWindow.top(item.slot(), item.stack());
        }
    }

    // ---- join -------------------------------------------------------------------------------------------------

    /**
     * Recovery on join (§5.7), before any interaction and with no IO: a pure function of the saved account, the
     * preloaded ledger and the inventory (I6). The legacy {@code gui_inputs} stacks (§10) are adopted as OWED entries
     * in the same pass, then everything owed tries the inventory. An unreadable account or ledger leaves the gate
     * {@link PlayerEscrow.Gate#UNRECOVERABLE} and touches nothing (K18).
     */
    public Recovered recover(PlayerEscrow escrow, Live<T> live, AccountRead saved, LedgerState ledger,
            List<T> legacy) {
        BagInventory<T> bag = new BagInventory<>(live, stacks);
        Recovery.Result result = ledger.recover(saved, bag);
        if (result instanceof Recovery.Result.Unrecoverable blocked) {
            escrow.unrecoverable(blocked.cause() + ": " + blocked.detail());
            return new Blocked(blocked.cause(), blocked.detail());
        }
        Recovery.Result.Recovered recovered = (Recovery.Result.Recovered) result;
        Work work = new Work(recovered.account());
        work.bytes = recovered.encoded().orElse(null);
        int returned = recovered.returned().size();
        int adopted = 0;
        boolean legacyKept = false;
        if (!legacy.isEmpty()) {
            List<Entry> entries = new ArrayList<>();
            for (T stack : legacy) {
                entries.add(new Entry(ids.get(), EntryState.OWED, Id128.ZERO, clock.getAsLong(), "legacy:gui_inputs", 0,
                        stacks.encode(stack)));
            }
            Work adoption = new Work(work.account);
            if (adoption.run(EscrowTransfer.adoptLegacy(adoption.account, entries))) {
                adopted = entries.size();
                work.account = adoption.account;
                work.bytes = adoption.bytes;
            } else {
                legacyKept = true;
            }
        }
        if (work.run(EscrowTransfer.claim(work.account, bag)) && work.last instanceof Step.Applied claimed) {
            returned += claimed.returned().size();
        }
        CrashGate.at(CrashGate.Gates.RECOVERY_BEFORE_COMMIT); // test-only, inert in production
        if (work.bytes != null) {
            escrow.commit(work.account, work.bytes);
        }
        escrow.open(work.account);
        CrashGate.at(CrashGate.Gates.RECOVERY_DONE); // test-only, inert in production
        return new Opened(returned, countOwed(work.account), recovered.dropped().size(), adopted, legacyKept);
    }

    // ---- shared -----------------------------------------------------------------------------------------------

    /** The entry that stands for the stack in window slot {@code slot}: HELD or EXITING with that slot hint. */
    public static Entry entryAt(Account account, int slot) {
        for (Entry entry : account.entries()) {
            if (entry.state() != EntryState.OWED && entry.slotHint() == slot) {
                return entry;
            }
        }
        return null;
    }

    /** How many entries of {@code account} are OWED. */
    public static int countOwed(Account account) {
        int owed = 0;
        for (Entry entry : account.entries()) {
            if (entry.state() == EntryState.OWED) {
                owed++;
            }
        }
        return owed;
    }

    private boolean matches(Entry entry, T content) {
        try {
            return entry.stack().equals(stacks.encode(content));
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private Entry held(StackBytes bytes, String gui, int slot) {
        return Entry.held(ids.get(), bytes, gui, slot, clock.getAsLong());
    }

    private StackBytes encode(Work work, T stack) {
        try {
            return stacks.encode(stack);
        } catch (RuntimeException unserialisable) {
            work.denied = new Denied<>(Reason.UNRECORDABLE, null);
            return null;
        }
    }

    /**
     * Makes the account describe what {@code slot} shows. {@code liveIsTruthWhenEmpty}: an empty slot drops its entry
     * (a click is about to change it); a release keeps it and gives it back instead.
     */
    private boolean sync(Work work, Live<T> live, String gui, int slot, boolean liveIsTruthWhenEmpty) {
        T content = live.top(slot);
        Entry entry = entryAt(work.account, slot);
        if (entry != null && entry.state() == EntryState.EXITING) {
            work.denied = new Denied<>(Reason.BUSY, Refusal.BUSY);
            return false;
        }
        if (stacks.empty(content)) {
            return entry == null || !liveIsTruthWhenEmpty
                    || work.run(EscrowTransfer.pickUp(work.account, entry.id()));
        }
        StackBytes bytes = encode(work, content);
        if (bytes == null) {
            return false;
        }
        if (entry != null && entry.stack().equals(bytes)) {
            return true;
        }
        if (entry != null && !work.run(EscrowTransfer.pickUp(work.account, entry.id()))) {
            return false;
        }
        return work.run(EscrowTransfer.place(work.account, held(bytes, gui, slot)));
    }

    /** Adopts every stack the escrowed slots hold that the account does not; the ones it cannot adopt are strays. */
    private List<T> adoptStrays(Work work, Live<T> live, String gui, Collection<Integer> slots) {
        List<T> strays = new ArrayList<>();
        for (int slot : new TreeSet<>(slots)) {
            T content = live.top(slot);
            Entry known = entryAt(work.account, slot);
            if (stacks.empty(content) || (known != null && known.state() == EntryState.EXITING)) {
                continue; // a frozen entry's stack stays shown in its slot until its exit completes: not a stray
            }
            Work attempt = new Work(work.account);
            attempt.bytes = work.bytes;
            if (sync(attempt, live, gui, slot, false)) {
                work.account = attempt.account;
                work.bytes = attempt.bytes;
            } else {
                strays.add(content);
            }
        }
        return strays;
    }

    private void clear(Live<T> live, Collection<Integer> slots) {
        for (int slot : slots) {
            if (!stacks.empty(live.top(slot))) {
                live.top(slot, null);
            }
        }
    }

    private void finish(PlayerEscrow escrow, Work work) {
        if (work.bytes != null) {
            escrow.commit(work.account, work.bytes);
        }
    }

    /** The account while a step is planned: each model step is chained onto it, and the last bytes win. */
    private final class Work {

        Account account;
        byte[] bytes;
        Denied<T> denied;
        Step last;
        int returned;

        Work(Account account) {
            this.account = account;
        }

        /** Takes a model step: false (with {@link #denied}) when it was refused. */
        boolean run(Step step) {
            last = step;
            switch (step) {
                case Step.Applied applied -> {
                    account = applied.account();
                    bytes = applied.encoded();
                    returned += applied.returned().size();
                    return true;
                }
                case Step.NoOp noOp -> {
                    return true;
                }
                case Step.Refused refused -> {
                    denied = new Denied<>(refused.reason() == Refusal.BUSY ? Reason.BUSY : Reason.UNRECORDABLE,
                            refused.reason());
                    return false;
                }
            }
        }
    }
}
