package dev.materione.behavior.logic;

import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.behavior.expr.FunctionResolver;
import dev.materione.behavior.rule.EventSelector;
import java.util.Locale;
import java.util.Optional;

/**
 * What the rule parser needs from the registries, and nothing more: resolve an author's spelling
 * of an action, a condition or a trigger selector to the registered type. Keeping it an interface
 * lets the engine be unit-tested without a server and lets the host decide where the registries
 * live.
 *
 * <p>Lookups happen at rule load, never on the hot path: a step holds the type it resolved, so
 * unregistering a type later does not break content that already loaded (it surfaces on the next
 * reload).
 */
public interface LogicLookup {

    /** The action an author's key names ({@code run_command}, {@code example:greet}). */
    Optional<ActionType<?>> action(String authoredId);

    /** The condition an author's key names. */
    Optional<ConditionType<?>> condition(String authoredId);

    /** True when {@code authoredId} names a registered trigger. */
    boolean triggerKnown(String authoredId);

    /**
     * The self-description of the trigger {@code authoredId} names (its context schema is what
     * {@code ${root}} paths are checked against, logic.md §2.6). Empty when the trigger is unknown
     * or the lookup cannot say; the compiler then skips the check rather than guess.
     */
    default Optional<TriggerDescription> trigger(String authoredId) {
        return Optional.empty();
    }

    /**
     * The expression functions a rule's expressions may call, bound at parse time (logic.md §4.2).
     * Empty by default, so a lookup with no function registry rejects every call.
     */
    default FunctionResolver functions() {
        return FunctionResolver.NONE;
    }

    /**
     * Resolves an {@code on:} value to a selector. Materione's own triggers are written bare, with
     * an optional detail after a colon ({@code click:right}); an addon trigger is written
     * qualified, with the detail after a second colon ({@code example:storm:heavy}). A first
     * segment that names a Materione trigger is therefore never read as a namespace.
     */
    default Optional<EventSelector> selector(String text) {
        String trimmed = text.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT); // ids are case-insensitive, details are not
        int first = lower.indexOf(':');
        String head = first < 0 ? lower : lower.substring(0, first);
        if (!head.isEmpty() && triggerKnown(head)) {
            return Optional.of(new EventSelector(head, detailOf(first < 0 ? null : trimmed.substring(first + 1))));
        }
        if (first >= 0) {
            int second = lower.indexOf(':', first + 1);
            String qualified = second < 0 ? lower : lower.substring(0, second);
            if (triggerKnown(qualified)) {
                // the canonical spelling: materione:click is just click, fired as such
                String type = LogicIds.parse(qualified).map(LogicIds::display).orElse(qualified);
                return Optional.of(new EventSelector(type,
                        detailOf(second < 0 ? null : trimmed.substring(second + 1))));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> detailOf(String detail) {
        return detail == null || detail.isBlank() ? Optional.empty() : Optional.of(detail.trim());
    }
}
