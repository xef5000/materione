package dev.materione.plugin.escrow.model;

import java.util.List;
import java.util.Objects;

/**
 * The result of one tier-1 or tier-2 step (gui-input-durability.md §5.4): the account after it and its eagerly
 * encoded bytes (the caller sets the player's persistent value to them in the same owner-thread step), or a refusal
 * that changed nothing.
 */
public sealed interface Step {

    /**
     * The step happened.
     *
     * @param account  the account after the step ({@code gen} advanced once)
     * @param encoded  {@link AccountCodec#encode} of {@code account}: the new persistent value
     * @param released stacks that left the account for the cursor or the death drop list: the caller owns them now
     * @param returned stacks the step put in the inventory
     * @param owed     stacks that are OWED after the step because they did not fit
     * @param consumed stacks deleted because their exit decision is durable
     */
    record Applied(
            Account account,
            byte[] encoded,
            List<StackBytes> released,
            List<StackBytes> returned,
            List<StackBytes> owed,
            List<StackBytes> consumed)
            implements Step {

        public Applied {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(encoded, "encoded");
            released = List.copyOf(released);
            returned = List.copyOf(returned);
            owed = List.copyOf(owed);
            consumed = List.copyOf(consumed);
        }
    }

    /**
     * Nothing to do (a second apply of the same exit, a release with nothing to release): the account is unchanged,
     * no generation is spent and nothing needs writing.
     *
     * @param account the unchanged account
     */
    record NoOp(Account account) implements Step {}

    /**
     * The step cannot be performed and has done nothing: the inventory and the account are exactly as before (I7).
     *
     * @param reason why
     */
    record Refused(Refusal reason) implements Step {}
}
