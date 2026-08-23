package dev.materione.core.blocks;

import java.util.List;

/**
 * Pure blockstate allocation spaces for the custom-block techniques
 * (runtime-systems §4.1). A technique's slot space is a bijection between
 * allocation slots and vanilla blockstate property sets: the compiler emits
 * one {@code blockstates/<host>.json} variant per allocated slot, the
 * runtime decodes a placed host's state back to its slot with integer math.
 * <p>
 * The tables are <strong>pure data, version-floored at 1.21.4</strong> so
 * pack output stays deterministic (D7) without a Bukkit dependency; a
 * platform-side conformance test asserts them against the live server's
 * enums. The note-block instrument order is an API contract: placed worlds
 * decode slots through this list, so reordering it would silently rewrite
 * every placed custom block (Nexo keeps the same list frozen for the same
 * reason).
 * <p>
 * Connection-bearing spaces ({@link Technique#STRING}, {@link
 * Technique#CHORUS}) encode their connection properties as identity bits —
 * that is only sound while host physics updates are suppressed (Paper's
 * {@code blocks-updates.disable-*-updates}); otherwise neighbor connections
 * would mutate placed states and rewrite identities at runtime.
 */
public final class BlockStateSpaces {

    /** The custom-block techniques. */
    public enum Technique {
        /** Hijacked note blocks (~1100 solid states) — general purpose. */
        NOTE_BLOCK,
        /** Hijacked tripwire (127 states) — foliage, plants, cross models. */
        STRING,
        /** Hijacked chorus plant (64 states) — transparent, glass-like. */
        CHORUS,
        /**
         * An author-chosen technical host (mushroom-stem class) rendering one
         * fixed model; identity is chunk-PDC carried, no state is allocated.
         */
        TECHNICAL
    }

    /** Note-block instruments in slot order (1.21.4). Order is an API contract. */
    private static final List<String> NOTE_BLOCK_INSTRUMENTS = List.of(
            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "chime",
            "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit",
            "banjo", "pling", "zombie", "skeleton", "creeper", "dragon",
            "wither_skeleton", "piglin", "custom_head");

    /** Notes per instrument × {unpowered, powered}: slot = idx*50 + note + (powered ? 25 : 0). */
    private static final int SLOTS_PER_INSTRUMENT = 50;
    private static final int NOTES_PER_INSTRUMENT = 25;

    /** Tripwire identity bits: east=1 west=2 south=4 north=8 attached=16 disarmed=32 powered=64. */
    public static final int TRIPWIRE_MIN = 1;
    public static final int TRIPWIRE_MAX = 127;

    /** Chorus identity bits: up=1 down=2 north=4 east=8 south=16 west=32 (slots 0..63). */
    public static final int CHORUS_MAX = 63;

    private BlockStateSpaces() {}

    /** The vanilla host block for a technique; technical blocks require an explicit host. */
    public static String hostOf(Technique technique, String configuredHost) {
        if (technique == Technique.TECHNICAL
                && (configuredHost == null || configuredHost.isBlank())) {
            throw new IllegalArgumentException("technical blocks require an explicit 'host:'");
        }
        return switch (technique) {
            case NOTE_BLOCK -> "note_block";
            case STRING -> "tripwire";
            case CHORUS -> "chorus_plant";
            case TECHNICAL -> configuredHost;
        };
    }

    /** Number of allocatable slots for a technique (0 = none; identity via PDC). */
    public static int capacity(Technique technique) {
        return switch (technique) {
            case NOTE_BLOCK -> NOTE_BLOCK_INSTRUMENTS.size() * SLOTS_PER_INSTRUMENT;
            case STRING -> TRIPWIRE_MAX - TRIPWIRE_MIN + 1;
            case CHORUS -> CHORUS_MAX + 1;
            case TECHNICAL -> 0;
        };
    }

    /** True when placements of this technique are identified by their blockstate. */
    public static boolean allocatesState(Technique technique) {
        return technique != Technique.TECHNICAL;
    }

    /**
     * The {@code blockstates/<host>.json} variant key for an allocated slot.
     *
     * @throws IllegalArgumentException when the slot is outside the space
     */
    public static String variantKey(Technique technique, int slot) {
        return switch (technique) {
            case NOTE_BLOCK -> {
                if (slot < 0 || slot >= capacity(Technique.NOTE_BLOCK)) {
                    throw outOfRange("note_block", slot);
                }
                int instrument = slot / SLOTS_PER_INSTRUMENT;
                int rest = slot % SLOTS_PER_INSTRUMENT;
                yield "instrument=" + NOTE_BLOCK_INSTRUMENTS.get(instrument)
                        + ",note=" + (rest % NOTES_PER_INSTRUMENT)
                        + ",powered=" + (rest >= NOTES_PER_INSTRUMENT);
            }
            case STRING -> {
                if (slot < TRIPWIRE_MIN || slot > TRIPWIRE_MAX) {
                    throw outOfRange("tripwire", slot);
                }
                yield "attached=" + bit(slot, 16)
                        + ",disarmed=" + bit(slot, 32)
                        + ",powered=" + bit(slot, 64)
                        + ",north=" + bit(slot, 8)
                        + ",east=" + bit(slot, 1)
                        + ",south=" + bit(slot, 4)
                        + ",west=" + bit(slot, 2);
            }
            case CHORUS -> {
                if (slot < 0 || slot > CHORUS_MAX) {
                    throw outOfRange("chorus_plant", slot);
                }
                yield "north=" + bit(slot, 4)
                        + ",east=" + bit(slot, 8)
                        + ",south=" + bit(slot, 16)
                        + ",west=" + bit(slot, 32)
                        + ",up=" + bit(slot, 1)
                        + ",down=" + bit(slot, 2);
            }
            case TECHNICAL -> throw new IllegalStateException("TECHNICAL allocates no state slot");
        };
    }

    /** The tripwire key reserved to slot-less wire: mapped to a barrier model so stray wire renders nothing. */
    public static String tripwireReservedKey() {
        return "attached=false,disarmed=false,powered=false,north=false,east=false,south=false,west=false";
    }

    /**
     * Splits a note-block slot into {@code [instrumentOrdinal, note, powered]}
     * — the runtime's decode side of the allocation formula. Tripwire and
     * chorus need no splitter: their slot <em>is</em> the identity bit
     * pattern (§4.1), validated by {@link #inRange}.
     *
     * @throws IllegalArgumentException when the slot is outside the space
     */
    public static int[] noteParts(int slot) {
        if (slot < 0 || slot >= capacity(Technique.NOTE_BLOCK)) {
            throw outOfRange("note_block", slot);
        }
        int rest = slot % SLOTS_PER_INSTRUMENT;
        return new int[] {slot / SLOTS_PER_INSTRUMENT, rest % NOTES_PER_INSTRUMENT, rest >= NOTES_PER_INSTRUMENT ? 1 : 0};
    }

    /** Inverse of {@link #noteParts} — the encode side used when placing. */
    public static int noteSlot(int instrumentOrdinal, int note, boolean powered) {
        if (instrumentOrdinal < 0 || instrumentOrdinal >= NOTE_BLOCK_INSTRUMENTS.size()) {
            throw new IllegalArgumentException("instrument ordinal " + instrumentOrdinal + " outside the table");
        }
        if (note < 0 || note >= NOTES_PER_INSTRUMENT) {
            throw outOfRange("note", note);
        }
        return instrumentOrdinal * SLOTS_PER_INSTRUMENT + note + (powered ? NOTES_PER_INSTRUMENT : 0);
    }

    /** True when {@code slot} is allocatable for {@code technique}. */
    public static boolean inRange(Technique technique, int slot) {
        return switch (technique) {
            case NOTE_BLOCK -> slot >= 0 && slot < capacity(Technique.NOTE_BLOCK);
            case STRING -> slot >= TRIPWIRE_MIN && slot <= TRIPWIRE_MAX;
            case CHORUS -> slot >= 0 && slot <= CHORUS_MAX;
            case TECHNICAL -> false;
        };
    }

    private static String bit(int value, int mask) {
        return (value & mask) != 0 ? "true" : "false";
    }

    private static IllegalArgumentException outOfRange(String host, int slot) {
        return new IllegalArgumentException("slot " + slot + " outside the " + host + " state space");
    }
}
