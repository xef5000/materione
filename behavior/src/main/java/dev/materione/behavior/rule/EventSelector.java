package dev.materione.behavior.rule;

import java.util.Optional;

/** Which event(s) a rule answers: {@code on: click:right} → type {@code click}, detail {@code right}. */
public record EventSelector(String type, Optional<String> detail) {

    public EventSelector {
        if (!EventTypes.isKnown(type)) {
            throw new IllegalArgumentException("unknown event type '" + type + "'");
        }
    }

    /** True when this selector matches a fired event of {@code type}/{@code detail}. */
    public boolean matches(String eventType, String eventDetail) {
        if (!type().equals(eventType)) {
            return false;
        }
        return detail().isEmpty() || detail().get().equals(eventDetail);
    }
}
