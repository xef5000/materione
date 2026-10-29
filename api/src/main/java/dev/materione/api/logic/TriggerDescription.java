package dev.materione.api.logic;

import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Objects;

/**
 * Everything the studio and the compiler need to know about a {@link TriggerType}
 * (logic.md §4.3).
 *
 * @param self display metadata; its {@code activation} sentence answers "when does this fire?"
 * @param details the selector details the runtime fires after a colon ({@code click:right}); empty
 *        when the trigger has none
 * @param firedBy the content types whose runtime fires this trigger today (item, block, furniture,
 *        later gui); an empty list means the trigger is part of the vocabulary but nothing fires it
 *        yet, so the editor must not offer it as a working trigger
 * @param filters the extra rule-level keys a rule may set on this trigger ({@code layer},
 *        {@code click}, {@code slot}, {@code every}), as a parameter schema
 * @param context the variables the trigger guarantees (always includes the universal roots the
 *        host documents), checked against {@code ${...}} paths and used for autocomplete
 * @param cancellable whether a rule may cancel the underlying event ({@code gui.opening} can,
 *        {@code gui.close} cannot); the editor offers {@code cancel} only on cancellable triggers,
 *        and a cancel request on any other is accepted but ignored ({@link Cancellation})
 */
public record TriggerDescription(
        SelfDescription self,
        List<String> details,
        List<String> firedBy,
        List<ParameterSchema> filters,
        List<ContextVariable> context,
        boolean cancellable) {

    public TriggerDescription {
        Objects.requireNonNull(self, "self");
        details = details == null ? List.of() : List.copyOf(details);
        firedBy = firedBy == null ? List.of() : List.copyOf(firedBy);
        filters = filters == null ? List.of() : List.copyOf(filters);
        context = context == null ? List.of() : List.copyOf(context);
    }

    /** Whether any runtime fires this trigger today. */
    public boolean fired() {
        return !firedBy.isEmpty();
    }
}
