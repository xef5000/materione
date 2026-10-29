package dev.materione.behavior.rule;

import java.util.Objects;
import java.util.Optional;

/**
 * Which event(s) a rule answers: {@code on: click:right} → type {@code click}, detail {@code right}.
 * The {@code type} is the canonical spelling of a registered trigger (bare for Materione's own,
 * qualified for an addon's); the parser resolves it through the trigger registry, so a selector
 * never names an unknown trigger.
 */
public record EventSelector(String type, Optional<String> detail) {

    public EventSelector {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(detail, "detail");
    }

    /** True when this selector matches a fired event of {@code type}/{@code detail}. */
    public boolean matches(String eventType, String eventDetail) {
        if (!type().equals(eventType)) {
            return false;
        }
        return detail().isEmpty() || detail().get().equals(eventDetail);
    }
}
