package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.io.AppendResult;
import dev.materione.plugin.escrow.io.EscrowLedgerStore;
import dev.materione.plugin.escrow.io.LedgerState;
import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.Entry;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.JointTransfer;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.LedgerRecord;
import dev.materione.plugin.escrow.model.StackBytes;
import dev.materione.plugin.escrow.model.Step;
import dev.materione.plugin.escrow.model.TransferHolder;
import dev.materione.plugin.escrow.gate.CrashGate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.inventory.ItemStack;

/**
 * The joint two-holder transfer. The one decision is a shared ledger record; neither side writes a consume record.
 */
public final class CustodyTransfer {

    /** Reserved file identity: the player UUID namespace cannot own this all-zero-like value. */
    public static final UUID SHARED_LEDGER = new UUID(0x6d74726564726300L, 0x0000000000000334L);

    public enum Reach {
        DECIDED,
        SAVED
    }

    public record Request(
            Id128 transaction,
            TransferHolder source,
            Id128 sourceEntry,
            TransferHolder destination,
            int destinationSlot,
            Reach reach) {}

    public sealed interface Outcome {
        record Transferred(Reach reached) implements Outcome {}
        record Refused(String reason) implements Outcome {}
    }

    private final EscrowRuntime runtime;
    private final EscrowLedgerStore ledger;
    private final SaveBarrier barrier;
    private final Supplier<Id128> ids;
    private final java.util.function.LongSupplier clock;
    private final ConcurrentMap<Id128, CompletableFuture<Outcome>> running = new ConcurrentHashMap<>();

    public CustodyTransfer(EscrowRuntime runtime, EscrowLedgerStore ledger, SaveBarrier barrier,
            Supplier<Id128> ids, java.util.function.LongSupplier clock) {
        this.runtime = runtime;
        this.ledger = ledger;
        this.barrier = barrier;
        this.ids = ids;
        this.clock = clock;
    }

    /** Starts or joins the idempotent transaction. */
    public CompletionStage<Outcome> transfer(Request request) {
        CompletableFuture<Outcome> outcome = new CompletableFuture<>();
        CompletableFuture<Outcome> existing = running.putIfAbsent(request.transaction(), outcome);
        if (existing != null) {
            return existing;
        }
        Ledger decided = sharedImage();
        if (decided.isTransferDecided(request.transaction())) {
            if (decided.isTransferDone(request.transaction()) || request.reach() == Reach.DECIDED) {
                finish(request.transaction(), outcome, new Outcome.Transferred(request.reach()));
            } else {
                finish(request.transaction(), outcome,
                        new Outcome.Refused("the transfer is decided but its saved acknowledgment is still pending"));
            }
            return outcome;
        }
        Optional<EscrowRuntime.TransferSide> sourceSide = runtime.transferSide(request.source());
        Optional<EscrowRuntime.TransferSide> destinationSide = runtime.transferSide(request.destination());
        if (sourceSide.isEmpty() || destinationSide.isEmpty()) {
            finish(request.transaction(), outcome, new Outcome.Refused("a transfer holder is unavailable"));
            return outcome;
        }
        if (request.source().equals(request.destination())) {
            finish(request.transaction(), outcome, new Outcome.Refused("source and destination must differ"));
            return outcome;
        }
        prepareSource(request, sourceSide.get(), destinationSide.get(), outcome);
        return outcome;
    }

    private void prepareSource(Request request, EscrowRuntime.TransferSide source,
            EscrowRuntime.TransferSide destination, CompletableFuture<Outcome> outcome) {
        onOwner(source.owner(), () -> {
            if (!source.account().open()) {
                finish(request.transaction(), outcome, new Outcome.Refused("source holder is not recoverable"));
                return;
            }
            Optional<Entry> entry = source.account().account().find(request.sourceEntry());
            if (entry.isEmpty()) {
                finish(request.transaction(), outcome, new Outcome.Refused("source entry is stale"));
                return;
            }
            Step step = JointTransfer.prepareSource(source.account().account(), request.sourceEntry(),
                    request.transaction());
            if (!commit(source.account(), step)) {
                finish(request.transaction(), outcome, new Outcome.Refused("source could not be prepared"));
                return;
            }
            CrashGate.at(CrashGate.Gates.TRANSFER_SOURCE_PREPARED);
            prepareDestination(request, source, destination, entry.get(), outcome);
        }, () -> finish(request.transaction(), outcome, new Outcome.Refused("source player left")));
    }

    private void prepareDestination(Request request, EscrowRuntime.TransferSide source,
            EscrowRuntime.TransferSide destination, Entry sourceEntry, CompletableFuture<Outcome> outcome) {
        Id128 reservation = ids.get();
        onOwner(destination.owner(), () -> {
            if (!destination.account().open()) {
                rollbackSource(request, source, outcome, "destination holder is not recoverable");
                return;
            }
            Step step = JointTransfer.reserve(destination.account().account(), sourceEntry, reservation,
                    request.transaction(), "custody.transfer", request.destinationSlot());
            if (!commit(destination.account(), step)) {
                rollbackSource(request, source, outcome, "destination could not be reserved");
                return;
            }
            CrashGate.at(CrashGate.Gates.TRANSFER_DESTINATION_RESERVED);
            checkpointPrepared(request, source, destination, sourceEntry, reservation, outcome);
        }, () -> rollbackSource(request, source, outcome, "destination player left"));
    }

    private void checkpointPrepared(Request request, EscrowRuntime.TransferSide source,
            EscrowRuntime.TransferSide destination, Entry sourceEntry, Id128 reservation,
            CompletableFuture<Outcome> outcome) {
        checkpointOwners(source, destination)
                .thenAccept(ok -> {
                    if (ok) {
                        CrashGate.at(CrashGate.Gates.TRANSFER_CHECKPOINTED);
                        decide(request, source, destination, sourceEntry, reservation, outcome);
                    } else {
                        rollbackBoth(request, source, destination, outcome, "preparation was not acknowledged");
                    }
                });
    }

    private void decide(Request request, EscrowRuntime.TransferSide source,
            EscrowRuntime.TransferSide destination, Entry sourceEntry, Id128 reservation,
            CompletableFuture<Outcome> outcome) {
        StackBytes bytes = sourceEntry.stack();
        LedgerRecord.TransferIntent intent = new LedgerRecord.TransferIntent(
                request.transaction(), request.source(), request.sourceEntry(),
                source.account().account().gen(), request.destination(), reservation,
                destination.account().account().gen(), bytes.hash16(),
                ItemStacks.INSTANCE.decode(bytes).getAmount(), clock.getAsLong());
        ledger.append(SHARED_LEDGER, intent).whenComplete((result, failure) -> {
            AppendResult outcomeNow = failure == null ? result
                    : new AppendResult.Refused(AppendResult.Reason.WRITE_FAILED, String.valueOf(failure));
            Ledger decided = outcomeNow instanceof AppendResult.Durable durable ? durable.ledger()
                    : sharedImage();
            if (!decided.isTransferDecided(request.transaction())) {
                rollbackBoth(request, source, destination, outcome, "joint decision could not be recorded");
                return;
            }
            CrashGate.at(CrashGate.Gates.TRANSFER_INTENT);
            commitBoth(request, source, destination, reservation, decided, intent, outcome);
        });
    }

    private void commitBoth(Request request, EscrowRuntime.TransferSide source,
            EscrowRuntime.TransferSide destination, Id128 reservation, Ledger decided,
            LedgerRecord.TransferIntent intent, CompletableFuture<Outcome> result) {
        onOwner(source.owner(), () -> {
            if (!commit(source.account(), JointTransfer.commitSource(
                    source.account().account(), decided, request.transaction(), request.sourceEntry()))) {
                returnRecoveryWillFinish(source, request.transaction(), result);
                return;
            }
            CrashGate.at(CrashGate.Gates.TRANSFER_SOURCE_COMMITTED);
            onOwner(destination.owner(), () -> {
                if (!commit(destination.account(), JointTransfer.commitDestination(
                        destination.account().account(), decided, request.transaction(), reservation,
                        request.destination().kind() == TransferHolder.Kind.KEYED))) {
                    returnRecoveryWillFinish(destination, request.transaction(), result);
                    return;
                }
                CrashGate.at(CrashGate.Gates.TRANSFER_DESTINATION_COMMITTED);
                if (request.reach() == Reach.DECIDED) {
                    finish(request.transaction(), result, new Outcome.Transferred(Reach.DECIDED));
                }
                acknowledge(request, source, destination, intent, result);
            }, () -> returnRecoveryWillFinish(destination, request.transaction(), result));
        }, () -> returnRecoveryWillFinish(source, request.transaction(), result));
    }

    private void acknowledge(Request request, EscrowRuntime.TransferSide source,
            EscrowRuntime.TransferSide destination, LedgerRecord.TransferIntent intent,
            CompletableFuture<Outcome> outcome) {
        checkpointOwners(source, destination)
                .thenAccept(ok -> {
                    if (!ok) {
                        return;
                    }
                    CrashGate.at(CrashGate.Gates.TRANSFER_ACKNOWLEDGED);
                    ledger.append(SHARED_LEDGER, new LedgerRecord.TransferDone(request.transaction()));
                    if (request.reach() == Reach.SAVED) {
                        finish(request.transaction(), outcome, new Outcome.Transferred(Reach.SAVED));
                    } else {
                        running.remove(request.transaction(), outcome);
                    }
                });
    }

    private void rollbackBoth(Request request, EscrowRuntime.TransferSide source,
            EscrowRuntime.TransferSide destination, CompletableFuture<Outcome> outcome, String reason) {
        onOwner(destination.owner(), () -> {
            commit(destination.account(),
                    JointTransfer.rollbackDestination(destination.account().account(), request.transaction()));
            rollbackSource(request, source, outcome, reason);
        }, () -> rollbackSource(request, source, outcome, reason));
    }

    private void rollbackSource(Request request, EscrowRuntime.TransferSide source,
            CompletableFuture<Outcome> outcome, String reason) {
        onOwner(source.owner(), () -> {
            commit(source.account(), JointTransfer.rollbackSource(
                    source.account().account(), new BagInventory<>(source.owner().live(), ItemStacks.INSTANCE),
                    request.transaction()));
            finish(request.transaction(), outcome, new Outcome.Refused(reason));
        }, () -> finish(request.transaction(), outcome, new Outcome.Refused(reason)));
    }

    private void returnRecoveryWillFinish(EscrowRuntime.TransferSide side, Id128 txn,
            CompletableFuture<Outcome> outcome) {
        finish(txn, outcome, new Outcome.Refused("the owner left; recovery will finish the durable transfer"));
    }

    private Ledger sharedImage() {
        return ledger.preloaded(SHARED_LEDGER)
                .filter(LedgerState.Ready.class::isInstance)
                .map(state -> ((LedgerState.Ready) state).ledger())
                .orElse(Ledger.EMPTY);
    }

    /**
     * A player's whole PDC compound is one snapshot. Touch and read back the default account once per owner; this
     * proves all keyed/player PDC preparations or commits saved in that player's same compound without guessing from
     * a second PDC key read.
     */
    private CompletionStage<Boolean> checkpointOwners(
            EscrowRuntime.TransferSide source, EscrowRuntime.TransferSide destination) {
        Map<UUID, EscrowRuntime.TransferSide> owners = new LinkedHashMap<>();
        owners.put(source.player().getUniqueId(), source);
        owners.put(destination.player().getUniqueId(), destination);
        CompletionStage<Boolean> result = CompletableFuture.completedFuture(true);
        for (EscrowRuntime.TransferSide side : owners.values()) {
            result = result.thenCompose(previous ->
                    previous ? checkpointOwner(side) : CompletableFuture.completedFuture(false));
        }
        return result;
    }

    private CompletionStage<Boolean> checkpointOwner(EscrowRuntime.TransferSide side) {
        PlayerEscrow anchor = runtime.of(side.player());
        Owner<ItemStack> owner = runtime.owner(side.player(), anchor);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        onOwner(owner, () -> {
            if (!anchor.touch()) {
                result.complete(false);
                return;
            }
            barrier.checkpoint(owner, runtime.accountKey(
                    new dev.materione.api.custody.CustodyHolder.Player(side.player().getUniqueId())).toString(),
                    anchor.account().gen()).thenAccept(result::complete);
        }, () -> result.complete(false));
        return result;
    }

    private static boolean commit(CustodyAccount account, Step step) {
        if (step instanceof Step.NoOp) {
            return true;
        }
        if (!(step instanceof Step.Applied applied)) {
            return false;
        }
        return account.commit(applied.account(), applied.encoded());
    }

    private static void onOwner(Owner<ItemStack> owner, Runnable task, Runnable retired) {
        owner.run(task, retired);
    }

    private void finish(Id128 txn, CompletableFuture<Outcome> outcome, Outcome value) {
        outcome.complete(value);
        if (!(value instanceof Outcome.Transferred transferred) || transferred.reached() == Reach.SAVED) {
            running.remove(txn, outcome);
        }
    }
}
