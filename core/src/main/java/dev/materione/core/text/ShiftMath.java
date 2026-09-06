package dev.materione.core.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shift font: a {@code type:space} provider whose characters carry fixed
 * power-of-two advances, so any pixel offset renders as a short run of
 * invisible characters. Font JSON has no per-glyph x/y offset, so horizontal
 * and vertical layout is composed from negative and positive advances — the
 * incumbents' proven technique (Nexo {@code Shift}, Oraxen {@code ShiftProvider}).
 *
 * <p>Codepoints live in the U+E100 private-use block, disjoint from the glyph
 * codepoint origin (42000). Pack emission and runtime text resolution must
 * agree on this scheme — both go through here.
 */
public final class ShiftMath {

    /** The negative-advance run, ascending by power (−1, −2, … −512). */
    public static final char FIRST_NEGATIVE = '\uE101';
    /** The positive-advance run, ascending by power (+1, +2, … +512). */
    public static final char FIRST_POSITIVE = '\uE111';
    /** Highest advance magnitude (2^9); larger offsets compose from repeats. */
    public static final int MAX_POWER = 9;
    /** Saturation bound for a single shift — anything larger is a config bug. */
    public static final int MAX_OFFSET = 65_536;

    /** One shift character and the advance it renders with. */
    public record Shift(char character, int advance) {}

    private ShiftMath() {}

    /** Every (character, advance) pair of the shift font, ascending magnitude. */
    public static Map<Character, Integer> advances() {
        var out = new LinkedHashMap<Character, Integer>();
        for (int power = 0; power <= MAX_POWER; power++) {
            out.put((char) (FIRST_NEGATIVE + power), -(1 << power));
        }
        for (int power = 0; power <= MAX_POWER; power++) {
            out.put((char) (FIRST_POSITIVE + power), 1 << power);
        }
        return out;
    }

    /**
     * Decomposes {@code offset} into shift characters greedily (largest power
     * first, repeating as needed); 0 yields none. The rendered advance equals
     * the offset exactly; magnitudes beyond {@link #MAX_OFFSET} saturate.
     */
    public static List<Shift> decompose(int offset) {
        var out = new ArrayList<Shift>();
        long remaining = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offset));
        while (remaining != 0) {
            boolean negative = remaining < 0;
            int power = 63 - Long.numberOfLeadingZeros(Math.abs(remaining));
            if (power > MAX_POWER) {
                power = MAX_POWER;
            }
            int advance = negative ? -(1 << power) : (1 << power);
            out.add(new Shift(
                    negative ? (char) (FIRST_NEGATIVE + power) : (char) (FIRST_POSITIVE + power),
                    advance));
            remaining -= advance;
        }
        return out;
    }
}
