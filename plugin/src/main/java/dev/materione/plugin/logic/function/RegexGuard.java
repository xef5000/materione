package dev.materione.plugin.logic.function;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bounded regular expressions for {@code matches} (logic.md §4.2). A pattern from content must not
 * be able to stall the server, so three limits hold, and a call that breaks one answers {@code false}:
 *
 * <ul>
 *   <li>the pattern is at most {@value #MAX_PATTERN_LENGTH} characters, and an invalid one never matches;</li>
 *   <li>the text is at most {@value #MAX_INPUT_LENGTH} characters;</li>
 *   <li>matching may read at most {@value #CHAR_BUDGET} characters of the text. Backtracking reads
 *       the text again on every step, so this bounds the work even for a pattern that backtracks
 *       exponentially, which a length cap alone cannot do.</li>
 * </ul>
 *
 * <p>Each distinct pattern compiles once; the cache holds at most {@value #MAX_CACHED} patterns, and
 * a pattern past that is compiled per call (still bounded by the limits above).
 */
final class RegexGuard {

    /** The longest pattern a call compiles. */
    static final int MAX_PATTERN_LENGTH = 256;

    /** The longest text a call matches; a longer text answers {@code false}. */
    static final int MAX_INPUT_LENGTH = 1024;

    /** The most characters one match may read from the text. */
    static final long CHAR_BUDGET = 1_000_000L;

    private static final int MAX_CACHED = 256;
    private static final Map<String, Optional<Pattern>> CACHE = new ConcurrentHashMap<>();

    private RegexGuard() {}

    /**
     * True when the whole of {@code text} matches {@code regex}. Every limit above is applied here, so
     * a false result covers "no match", "invalid pattern" and "limit reached" alike.
     */
    static boolean fullMatch(String regex, String text) {
        if (text.length() > MAX_INPUT_LENGTH) {
            return false;
        }
        Optional<Pattern> pattern = compile(regex);
        if (pattern.isEmpty()) {
            return false;
        }
        try {
            return pattern.get().matcher(new BudgetedText(text, CHAR_BUDGET)).matches();
        } catch (BudgetExceeded exceeded) {
            return false;
        }
    }

    private static Optional<Pattern> compile(String regex) {
        if (regex.length() > MAX_PATTERN_LENGTH) {
            return Optional.empty();
        }
        Optional<Pattern> cached = CACHE.get(regex);
        if (cached != null) {
            return cached;
        }
        Optional<Pattern> compiled;
        try {
            compiled = Optional.of(Pattern.compile(regex));
        } catch (PatternSyntaxException invalid) {
            compiled = Optional.empty();
        }
        if (CACHE.size() < MAX_CACHED) {
            CACHE.putIfAbsent(regex, compiled);
        }
        return compiled;
    }

    /** Thrown from inside the matcher when the character budget runs out; never escapes {@link #fullMatch}. */
    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** The text as the matcher reads it: every character read is charged to the budget. */
    private static final class BudgetedText implements CharSequence {

        private final CharSequence text;
        private long remaining;

        BudgetedText(CharSequence text, long budget) {
            this.text = text;
            this.remaining = budget;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (--remaining < 0) {
                throw new BudgetExceeded();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
