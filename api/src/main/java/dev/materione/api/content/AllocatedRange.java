package dev.materione.api.content;

/**
 * One durable consecutive run from the allocation table — the shape glyph
 * codepoints need (a sheet's rows and an animation's frames must occupy
 * adjacent codepoints to form one bitmap provider's char strings).
 *
 * <p>Stored as the opaque table value {@code "start,count"}; a count of 1
 * collapses to the plain-int form {@code "n"} so single allocations of any
 * kind stay homogeneous on disk.
 */
public record AllocatedRange(int start, int count) {

    public AllocatedRange {
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1: " + count);
        }
    }

    /** Parses a table value: {@code "n"} (a count-1 run) or {@code "start,count"}. */
    public static AllocatedRange parse(String value) {
        int comma = value.indexOf(',');
        if (comma < 0) {
            return new AllocatedRange(Integer.parseInt(value), 1);
        }
        return new AllocatedRange(
                Integer.parseInt(value.substring(0, comma)),
                Integer.parseInt(value.substring(comma + 1)));
    }

    /** The highest allocated integer of the run (what the kind's counter must reach). */
    public int last() {
        return start + count - 1;
    }

    @Override
    public String toString() {
        return count == 1 ? Integer.toString(start) : start + "," + count;
    }
}
