package dev.materione.plugin.escrow.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * The new design (gui-input-durability.md §5): a live player whose every ownership change goes through the pure
 * model's one tier-1 API, with the account encoded eagerly into the persistent value, exit decisions appended to a
 * byte-exact ledger file as separate asynchronous steps, and recovery by {@link Recovery}. Every model call here is
 * the production call; this class only supplies the world around it (inventory, cursor, saves, the append queue).
 *
 * <p>{@link Mutation}s are deliberately broken variants: the test must catch each one, which proves the oracle can
 * fail where the design says it must not.
 */
final class EscrowWorld implements Design {

    /** Ways to break the design on purpose. */
    enum Mutation {
        NONE,
        /** Recovery forgets to read the ledger: a consumed entry resurrects (G2). */
        IGNORE_LEDGER,
        /** What does not fit on recovery is dropped at the feet instead of staying owed (G4, G1). */
        DROP_ON_OVERFLOW
    }

    static final int CAPACITY = 4;

    private final Mutation mutation;
    private final Fx.ListInventory inventory = new Fx.ListInventory(CAPACITY);
    private final Snapshot initial;
    private final List<Saved> saves = new ArrayList<>();
    private final ArrayDeque<LedgerRecord> pending = new ArrayDeque<>();
    private final Map<Id128, Long> exitGen = new HashMap<>();
    private final Set<Id128> ackQueued = new HashSet<>();
    private final List<String> violations = new ArrayList<>();

    private StackBytes cursor;
    private Account account = Account.EMPTY;
    private byte[] data;
    private byte[] ledgerFile = new byte[0];
    private Ledger durable = Ledger.EMPTY;
    private long lastSavedGen = -1;
    private boolean online = true;
    private int tokens;
    private int ids;
    private int txns;
    private int stepNo;

    EscrowWorld(long seed, Mutation mutation) {
        this.mutation = mutation;
        var rng = new java.util.SplittableRandom(seed ^ 0x5DEECE66DL);
        for (int i = rng.nextInt(1, 4); i > 0; i--) {
            give();
        }
        this.initial = new Snapshot(List.copyOf(inventory.stacks), null);
    }

    // ---- Design -----------------------------------------------------------------------------------------------

    @Override
    public Snapshot initial() {
        return initial;
    }

    @Override
    public List<Saved> saves() {
        return saves;
    }

    @Override
    public byte[] ledgerFile() {
        return ledgerFile.clone();
    }

    @Override
    public byte[] nextAppend() {
        LedgerRecord next = pending.peek();
        if (next == null) {
            return null;
        }
        byte[] record = LedgerCodec.encodeRecord(next);
        return ledgerFile.length == 0 ? Design.concat(LedgerCodec.header(), record) : record;
    }

    @Override
    public List<StackBytes> liveHoldings() {
        List<StackBytes> all = new ArrayList<>(inventory.stacks);
        account.entries().forEach(entry -> all.add(entry.stack()));
        return all;
    }

    @Override
    public List<String> liveViolations() {
        return violations;
    }

    @Override
    public List<Held> escrowed(Snapshot snapshot) {
        List<Held> held = new ArrayList<>();
        if (AccountCodec.read(snapshot.data()) instanceof AccountRead.Readable readable) {
            readable.account().entries().forEach(entry -> held.add(new Held(entry.id(), entry.stack())));
        }
        return held;
    }

    @Override
    public Outcome recover(Snapshot snapshot, byte[] file) {
        LedgerRead ledger = mutation == Mutation.IGNORE_LEDGER
                ? new LedgerRead.Valid(Ledger.EMPTY, 0, 0)
                : LedgerCodec.read(file);
        var inv = new CountingInventory(snapshot.inventory(), mutation == Mutation.DROP_ON_OVERFLOW);
        Recovery.Result result = Recovery.recover(AccountCodec.read(snapshot.data()), ledger, inv);
        if (!(result instanceof Recovery.Result.Recovered recovered)) {
            throw new AssertionError("a readable account and a valid ledger must recover, got " + result);
        }
        byte[] nextData = recovered.encoded().orElse(snapshot.data());
        List<StackBytes> owed = new ArrayList<>(recovered.owed());
        return new Outcome(inv.stacks, owed, inv.dropped, new Snapshot(List.copyOf(inv.stacks), nextData));
    }

    /** The port over a list; with {@code dropOverflow} it is the mutant that "gives" overflow to the world. */
    private static final class CountingInventory implements Inventory {
        final List<StackBytes> stacks;
        final boolean dropOverflow;
        int dropped;

        CountingInventory(List<StackBytes> initial, boolean dropOverflow) {
            this.stacks = new ArrayList<>(initial);
            this.dropOverflow = dropOverflow;
        }

        @Override
        public boolean remove(StackBytes stack) {
            return stacks.remove(stack);
        }

        @Override
        public boolean tryAdd(StackBytes stack) {
            if (stacks.size() < CAPACITY) {
                stacks.add(stack);
                return true;
            }
            if (dropOverflow) {
                dropped++;
                return true;
            }
            return false;
        }
    }

    // ---- steps ------------------------------------------------------------------------------------------------

    @Override
    public String step(RandomGenerator rng, int number) {
        this.stepNo = number;
        List<Supplier<String>> options = new ArrayList<>();
        if (!online) {
            options.add(pending.isEmpty() ? this::rejoin : this::append);
            return options.get(0).get();
        }
        List<Entry> held = entries(EntryState.HELD);
        List<Entry> owed = entries(EntryState.OWED);
        List<Entry> frozen = entries(EntryState.EXITING);
        List<Entry> releasable = new ArrayList<>(held);
        releasable.addAll(owed);

        if (inventory.stacks.size() < CAPACITY) {
            weight(options, 2, this::give);
        }
        if (!inventory.stacks.isEmpty()) {
            weight(options, 4, () -> moveIn(rng));
            weight(options, 2, () -> beginExit(rng));
        }
        if (!releasable.isEmpty()) {
            weight(options, 3, () -> moveOut(pick(rng, releasable)));
        }
        if (!held.isEmpty()) {
            weight(options, 2, () -> editStack(pick(rng, held)));
            weight(options, 1, () -> reslot(pick(rng, held), rng.nextInt(54)));
            weight(options, 2, () -> freeze(pick(rng, held)));
            if (cursor == null) {
                weight(options, 2, () -> pickUp(pick(rng, held)));
            } else {
                weight(options, 2, () -> swap(pick(rng, held)));
            }
        }
        if (cursor != null) {
            weight(options, 3, () -> place(rng));
        }
        weight(options, 3, this::close);
        weight(options, 1, this::claim);
        weight(options, 5, this::save);
        weight(options, 1, () -> death(rng.nextBoolean()));
        weight(options, 1, this::quit);
        if (!pending.isEmpty()) {
            weight(options, 4, this::append);
            if (!entries(EntryState.EXITING).isEmpty()) {
                weight(options, 1, () -> abort(pick(rng, frozen)));
            }
        }
        for (Entry entry : frozen) {
            if (durable.isDecided(entry.txn())) {
                weight(options, 3, () -> complete(entry));
            }
        }
        for (Map.Entry<Id128, Long> exit : exitGen.entrySet()) {
            if (!ackQueued.contains(exit.getKey())
                    && Exits.acknowledge(durable, exit.getKey(), exit.getValue(), lastSavedGen).isPresent()) {
                weight(options, 3, () -> acknowledge(exit.getKey()));
            }
        }
        String done = pick(rng, options).get();
        checkLive();
        return done;
    }

    private static void weight(List<Supplier<String>> options, int times, Supplier<String> option) {
        for (int i = 0; i < times; i++) {
            options.add(option);
        }
    }

    private static <T> T pick(RandomGenerator rng, List<T> items) {
        return items.get(rng.nextInt(items.size()));
    }

    private List<Entry> entries(EntryState state) {
        return account.entries().stream().filter(entry -> entry.state() == state).toList();
    }

    private Entry fresh(StackBytes stack, int slot) {
        return Entry.held(Fx.id(++ids), stack, "gui", slot, stepNo);
    }

    /** Runs a model call; on a refusal the inventory must be exactly as before (I7). */
    private Step.Applied run(Supplier<Step> call) {
        List<StackBytes> before = new ArrayList<>(inventory.stacks);
        Step step = call.get();
        if (step instanceof Step.Refused refused && !before.equals(inventory.stacks)) {
            violations.add("a refused step (" + refused.reason() + ") changed the inventory");
        }
        if (step instanceof Step.Applied applied) {
            account = applied.account();
            data = applied.encoded();
            return applied;
        }
        return null;
    }

    private String give() {
        String name = "t" + (++tokens);
        inventory.tryAdd(Fx.stack(name));
        return "give " + name;
    }

    private String moveIn(RandomGenerator rng) {
        StackBytes stack = pick(rng, inventory.stacks);
        run(() -> EscrowTransfer.moveIn(account, inventory, fresh(stack, rng.nextInt(27))));
        return "moveIn " + Design.name(stack);
    }

    private String moveOut(Entry entry) {
        run(() -> EscrowTransfer.moveOut(account, inventory, entry.id()));
        return "moveOut " + Design.name(entry.stack());
    }

    private String pickUp(Entry entry) {
        Step.Applied step = run(() -> EscrowTransfer.pickUp(account, entry.id()));
        if (step != null) {
            cursor = step.released().get(0);
        }
        return "pickUp " + Design.name(entry.stack());
    }

    private String place(RandomGenerator rng) {
        Step.Applied step = run(() -> EscrowTransfer.place(account, fresh(cursor, rng.nextInt(27))));
        if (step != null) {
            cursor = null;
        }
        return "place";
    }

    private String swap(Entry entry) {
        Step.Applied step = run(() -> EscrowTransfer.swap(account, entry.id(), fresh(cursor, entry.slotHint())));
        if (step != null) {
            cursor = step.released().get(0);
        }
        return "swap " + Design.name(entry.stack());
    }

    private String close() {
        run(() -> EscrowTransfer.close(account, inventory));
        return "close";
    }

    private String claim() {
        run(() -> EscrowTransfer.claim(account, inventory));
        return "claim";
    }

    private String editStack(Entry entry) {
        run(() -> EscrowTransfer.editStack(account, entry.id(), Fx.stack(Design.name(entry.stack()) + "'")));
        return "edit " + Design.name(entry.stack());
    }

    private String reslot(Entry entry, int slot) {
        run(() -> EscrowTransfer.reslot(account, entry.id(), slot));
        return "reslot";
    }

    private Id128 txn() {
        return Fx.id(100_000L + ++txns);
    }

    private String freeze(Entry entry) {
        Id128 txn = txn();
        Step.Applied step = run(() -> Exits.freeze(account, entry.id(), txn));
        if (step != null) {
            decide(step, txn, entry.id());
        }
        return "freeze " + Design.name(entry.stack());
    }

    private String beginExit(RandomGenerator rng) {
        StackBytes stack = pick(rng, inventory.stacks);
        Id128 txn = txn();
        Entry entry = fresh(stack, rng.nextInt(27)).exiting(txn);
        Step.Applied step = run(() -> Exits.beginExit(account, inventory, entry));
        if (step != null) {
            decide(step, txn, entry.id());
        }
        return "beginExit " + Design.name(stack);
    }

    /** Step 1 happened: remember its generation and queue the INTENT for the asynchronous append (step 2). */
    private void decide(Step.Applied step, Id128 txn, Id128 entryId) {
        exitGen.put(txn, step.account().gen());
        pending.add(Exits.intent(step.account().find(entryId).orElseThrow(), ExitKind.CONSUME, 1, stepNo));
    }

    private String append() {
        byte[] bytes = nextAppend();
        LedgerRecord record = pending.poll();
        ledgerFile = Design.concat(ledgerFile, bytes);
        durable = durable.appended(record);
        return "append " + record.getClass().getSimpleName();
    }

    private String complete(Entry entry) {
        run(() -> Exits.complete(account, durable, entry.txn()));
        return "complete " + Design.name(entry.stack());
    }

    private String acknowledge(Id128 txn) {
        ackQueued.add(txn);
        pending.add(new LedgerRecord.Done(txn));
        return "queue DONE";
    }

    private String abort(Entry entry) {
        boolean failedAppend = pending.removeIf(record ->
                record instanceof LedgerRecord.Intent intent && intent.entryId().equals(entry.id()));
        if (failedAppend) {
            run(() -> Exits.abortUndecided(account, durable, inventory, entry.txn()));
        }
        return "abort " + Design.name(entry.stack());
    }

    private String save() {
        saves.add(new Saved(stepNo, new Snapshot(List.copyOf(inventory.stacks), data == null ? null : data.clone())));
        lastSavedGen = data == null ? -1 : account.gen();
        return "save";
    }

    private String death(boolean keepInventory) {
        // what the step releases without keepInventory is appended to the event's drop list: the world's, outside
        // the guarantee once dropped (K9); vanilla drops the rest of the inventory and the cursor the same way
        run(() -> EscrowTransfer.death(account, durable, inventory, keepInventory));
        if (!keepInventory) {
            inventory.stacks.clear();
        }
        cursor = null;
        return "death keep=" + keepInventory;
    }

    private String quit() {
        if (cursor != null) {
            inventory.tryAdd(cursor);
            cursor = null;
        }
        run(() -> EscrowTransfer.close(account, inventory));
        save();
        online = false;
        return "quit";
    }

    private String rejoin() {
        Snapshot last = saves.isEmpty() ? initial : saves.get(saves.size() - 1).snapshot();
        Outcome outcome = recover(last, ledgerFile);
        inventory.stacks.clear();
        inventory.stacks.addAll(outcome.inventory());
        data = outcome.next().data();
        account = data == null ? Account.EMPTY : ((AccountRead.Readable) AccountCodec.decode(data)).account();
        cursor = null;
        online = true;
        return "rejoin";
    }

    private void checkLive() {
        List<StackBytes> all = liveHoldings();
        if (cursor != null) {
            all.add(cursor);
        }
        Design.multiset(all).forEach((name, count) -> {
            if (count > 1) {
                violations.add("step " + stepNo + ": " + name + " is held " + count + " times");
            }
        });
        AccountRead read = AccountCodec.read(data);
        if (data != null && !read.equals(new AccountRead.Readable(account))) {
            violations.add("step " + stepNo + ": the persistent value does not match the live account");
        }
    }
}
