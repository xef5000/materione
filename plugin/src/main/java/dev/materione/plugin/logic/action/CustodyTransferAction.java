package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Quantity;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.TransferHolder;
import dev.materione.plugin.escrow.runtime.CustodyTransfer;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.GuiRun;
import dev.materione.plugin.gui.input.InputSlotPort;
import dev.materione.api.custody.CustodyKey;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/** {@code custody.transfer}: one durable joint decision between two transfer-capable custody holders. */
public final class CustodyTransferAction implements ActionType<CustodyTransferAction.Params> {

    public record Holder(Kind kind, TemplateText player, TemplateText key) {
        public enum Kind {
            PLAYER,
            KEYED
        }
    }

    public record Source(Holder holder, Quantity slot, TemplateText entry) {}
    public record Destination(Holder holder, Quantity slot) {}
    public record Params(Source source, Destination destination, TemplateText transaction,
            CustodyTransfer.Reach reach) {}

    private final GuiBridge bridge;

    public CustodyTransferAction(GuiBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public String id() {
        return "custody.transfer";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Transfer held items",
                "Atomically moves one held custody entry from source to destination with one joint durable decision. "
                        + "Source is {holder, entry} or a GUI {slot}; destination is {holder, slot}. "
                        + "Stack holders are refused because stack PDC has no acknowledged save barrier",
                List.of(
                        new ParameterSchema("source", ParameterType.RAW, true, null,
                                "A {holder, entry} exact address, or {slot} of the run's open GUI", null, null)
                                .ofKind(dev.materione.api.registry.ParameterSchema.KIND_CUSTODY_TRANSFER_SOURCE),
                        new ParameterSchema("destination", ParameterType.RAW, true, null,
                                "A {holder, slot}; holder is player or {kind: keyed, key, player?}", null, null)
                                .ofKind(dev.materione.api.registry.ParameterSchema.KIND_CUSTODY_TRANSFER_DESTINATION),
                        new ParameterSchema("id", ParameterType.STRING, false, null,
                                "Optional stable 32-digit transaction id; reuse observes the idempotent result",
                                null, null).acceptingExpression(),
                        new ParameterSchema("await", ParameterType.ENUM, false, "decided",
                                "decided after both owner commits, saved after both committed snapshots are acknowledged",
                                List.of("decided", "saved"), null)),
                "1.0.0"), LogicCategory.PLAYER, "source");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public boolean takesElse() {
        return true;
    }

    @Override
    public Params parse(ParamReader params) {
        ParamReader source = params.section("source");
        boolean slot = source.has("slot");
        boolean entry = source.has("entry");
        if (slot == entry) {
            throw new IllegalArgumentException("source must have exactly one of slot or entry");
        }
        Holder sourceHolder = source.has("holder") ? holder(source.section("holder"), true) : null;
        if (!slot && sourceHolder == null) {
            throw new IllegalArgumentException("an exact source entry needs holder");
        }
        if (slot && sourceHolder != null) {
            throw new IllegalArgumentException("a slot source takes its holder from the GUI layer");
        }
        Source from = new Source(sourceHolder,
                slot ? Quantity.requiredCount(source, "slot", 0, 53) : null,
                entry ? staticId(source.template("entry"), "source.entry") : null);
        ParamReader destination = params.section("destination");
        Destination to = new Destination(holder(destination.section("holder"), false),
                Quantity.requiredCount(destination, "slot", 0, 53));
        TemplateText transaction = params.has("id") ? staticId(params.template("id"), "id") : null;
        CustodyTransfer.Reach reach = CustodyTransfer.Reach.valueOf(
                params.choice("await", "decided", List.of("decided", "saved")).toUpperCase(Locale.ROOT));
        return new Params(from, to, transaction, reach);
    }

    private Holder holder(ParamReader params, boolean source) {
        if (!params.has("kind")) {
            throw new IllegalArgumentException("holder.kind is required");
        }
        String kind = params.choice("kind", "", List.of("player", "keyed", "stack"));
        if ("stack".equals(kind)) {
            throw new IllegalArgumentException("stack custody cannot acknowledge a transfer");
        }
        TemplateText player = params.has("player") ? staticPlayer(params.template("player")) : null;
        if ("player".equals(kind)) {
            if (params.has("key") || params.keys().stream().anyMatch(key ->
                    !List.of("kind", "player").contains(key))) {
                throw new IllegalArgumentException("a player holder has no key");
            }
            return new Holder(Holder.Kind.PLAYER, player, null);
        }
        if (params.keys().stream().anyMatch(key -> !List.of("kind", "player", "key").contains(key))) {
            throw new IllegalArgumentException("a keyed holder has only kind, player and key");
        }
        TemplateText key = params.template("key");
        if (isLiteral(key)) {
            CustodyKey.require(key.source());
        }
        return new Holder(Holder.Kind.KEYED, player, key);
    }

    private static TemplateText staticId(TemplateText value, String name) {
        if (isLiteral(value)) {
            try {
                Id128.parse(value.source());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(name + " must be a 32-digit id: " + invalid.getMessage());
            }
        }
        return value;
    }

    private static TemplateText staticPlayer(TemplateText value) {
        if (isLiteral(value)) {
            String text = value.source();
            boolean uuid = false;
            try {
                UUID.fromString(text);
                uuid = true;
            } catch (IllegalArgumentException ignored) {
                // Names are validated below.
            }
            if (!uuid && !text.matches("[A-Za-z0-9_]{3,16}")) {
                throw new IllegalArgumentException(
                        "holder.player must be a UUID or a canonical Minecraft player name: '" + text + "'");
            }
        }
        return value;
    }

    private static boolean isLiteral(TemplateText value) {
        String source = value.source();
        return !source.contains("${") && !source.contains("%");
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return ActionOutcome.failed("custody.transfer waits for the joint ledger and runs only inside a flow");
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        return reads.on(threading(), () -> prepare(params, context))
                .thenCompose(prepared -> prepared);
    }

    private CompletionStage<ActionOutcome> prepare(Params params, LogicContext context) {
        final UUID fallback;
        try {
            Player player = context.facet(Facets.PLAYER).orElse(null);
            fallback = player == null ? null : player.getUniqueId();
        } catch (RuntimeException failed) {
            return failed("event has no player for custody.transfer: " + failed);
        }
        if (fallback == null) {
            return failed("event has no player");
        }
        try {
            TransferHolder source = params.source().holder() == null ? null
                    : resolve(params.source().holder(), context, fallback);
            TransferHolder destination = resolve(params.destination().holder(), context, fallback);
            OptionalInt slot = OptionalInt.empty();
            if (params.source().slot() != null) {
                slot = params.source().slot().wholeValue(context);
                if (slot.isEmpty()) {
                    return failed("source slot must render to a whole number");
                }
            }
            Id128 entry = params.source().entry() == null ? null
                    : Id128.parse(params.source().entry().render(context));
            Id128 transaction = params.transaction() == null ? null
                    : Id128.parse(params.transaction().render(context));
            OptionalInt destinationSlot = params.destination().slot().wholeValue(context);
            if (destinationSlot.isEmpty()) {
                return failed("destination slot must render to a whole number");
            }
            boolean slotSource = params.source().slot() != null;
            GuiRun run = slotSource ? GuiRun.ofOpenSession(bridge, context) : GuiRun.ofPlayer(bridge, context);
            InputSlotPort.Transfer transfer = new InputSlotPort.Transfer(
                    source, slot.isPresent() ? slot.getAsInt() : null, entry,
                    destination, destinationSlot.getAsInt(), transaction, params.reach());
            return run.services().inputs().transfer(run.viewer().player(), run.record(), transfer)
                    .thenApply(outcome -> switch (outcome) {
                        case InputSlotPort.TransferOutcome.Transferred ignored -> ActionOutcome.OK;
                        case InputSlotPort.TransferOutcome.Refused refused -> ActionOutcome.failed(refused.reason());
                        case InputSlotPort.TransferOutcome.RecoveryPending pending ->
                                ActionOutcome.stopped(pending.reason());
                    });
        } catch (RuntimeException refused) {
            return failed(refused.getMessage());
        }
    }

    private static TransferHolder resolve(Holder holder, LogicContext context, UUID fallback) {
        UUID player = fallback;
        if (holder.player() != null) {
            String address = holder.player().render(context);
            try {
                player = UUID.fromString(address);
            } catch (IllegalArgumentException notUuid) {
                var online = org.bukkit.Bukkit.getPlayerExact(address);
                if (online == null) {
                    throw new IllegalArgumentException("holder player '" + address + "' is not online");
                }
                player = online.getUniqueId();
            }
        }
        Id128 owner = new Id128(player.getMostSignificantBits(), player.getLeastSignificantBits());
        return holder.kind() == Holder.Kind.PLAYER
                ? new TransferHolder(owner, TransferHolder.Kind.PLAYER, "")
                : new TransferHolder(owner, TransferHolder.Kind.KEYED,
                        CustodyKey.require(holder.key().render(context)));
    }

    private static CompletionStage<ActionOutcome> failed(String reason) {
        return CompletableFuture.completedFuture(ActionOutcome.failed(reason));
    }
}
