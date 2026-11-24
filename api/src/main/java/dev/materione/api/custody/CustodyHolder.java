package dev.materione.api.custody;

import dev.materione.api.logic.variable.StackRef;
import java.util.Objects;
import java.util.UUID;

/**
 * A resolved custody address. The player is part of a keyed address because the shipped durable account backing is the
 * player's own snapshot.
 */
public sealed interface CustodyHolder permits CustodyHolder.Player, CustodyHolder.Keyed, CustodyHolder.Stack {

    /** The player whose durable snapshot/owner thread contains this holder. */
    UUID owner();

    /** The player's default account: the omission-compatible form. */
    record Player(UUID owner) implements CustodyHolder {
        public Player {
            Objects.requireNonNull(owner, "owner");
        }
    }

    /** One canonical keyed account in the player's PDC. */
    record Keyed(UUID owner, String key) implements CustodyHolder {
        public Keyed {
            Objects.requireNonNull(owner, "owner");
            key = CustodyKey.require(key);
        }
    }

    /** A live stack's own PDC account. The ref's liveness and stable id are checked on every access. */
    record Stack(UUID owner, StackRef stack) implements CustodyHolder {
        public Stack {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(stack, "stack");
            if (!Objects.equals(owner, stack.owner())) {
                throw new IllegalArgumentException("stack owner does not match custody holder owner");
            }
        }
    }
}
