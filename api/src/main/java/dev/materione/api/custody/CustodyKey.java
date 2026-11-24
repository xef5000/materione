package dev.materione.api.custody;

import java.util.regex.Pattern;

/** A canonical, author-addressable custody key. Canonicalization never guesses a different key. */
public final class CustodyKey {

    /** The longest canonical key accepted by every persistence adapter. */
    public static final int MAX_LENGTH = 192;

    private static final Pattern CANONICAL =
            Pattern.compile("[a-z0-9][a-z0-9._:/-]{0," + (MAX_LENGTH - 1) + "}");

    private CustodyKey() {}

    /** @throws IllegalArgumentException when {@code value} is not already canonical */
    public static String require(String value) {
        if (value == null || !CANONICAL.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException(
                    "custody key must be canonical lowercase ASCII ([a-z0-9][a-z0-9._:/-], at most "
                            + MAX_LENGTH + " characters, no '..'): '" + value + "'");
        }
        return value;
    }

    /** Whether {@code value} is accepted by {@link #require(String)}. */
    public static boolean valid(String value) {
        try {
            require(value);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
