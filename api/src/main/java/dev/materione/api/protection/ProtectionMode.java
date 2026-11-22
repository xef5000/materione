package dev.materione.api.protection;

import java.util.Locale;
import java.util.Optional;

/**
 * How the port and the event path combine (protection.md section 4), the {@code protection_mode} setting. Deny wins and
 * abstain falls through in every mode.
 */
public enum ProtectionMode {

    /** Only the event path: the port is not asked by a verb. The behavior before the port existed. */
    EVENT,
    /** Only the port: a verb asks the providers and nothing else, and no event is raised for the check. */
    PORT,
    /** The port answers first and denies early, then the event path runs for a player's action. The default. */
    BOTH;

    /** What a server gets when it writes nothing. */
    public static final ProtectionMode DEFAULT = BOTH;

    /** The mode a setting value names, ignoring case; empty for anything else. */
    public static Optional<ProtectionMode> parse(Object value) {
        if (!(value instanceof String text)) {
            return Optional.empty();
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "event" -> Optional.of(EVENT);
            case "port" -> Optional.of(PORT);
            case "both" -> Optional.of(BOTH);
            default -> Optional.empty();
        };
    }

    /** Whether a verb asks the providers in this mode. */
    public boolean asksPort() {
        return this != EVENT;
    }

    /** Whether a verb's player action also runs the event path in this mode. */
    public boolean runsEventPath() {
        return this != PORT;
    }
}
