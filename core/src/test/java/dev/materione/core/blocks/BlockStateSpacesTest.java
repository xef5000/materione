package dev.materione.core.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.core.blocks.BlockStateSpaces.Technique;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The slot↔blockstate bijections are load-bearing: the pack emits variants
 * from slots and the runtime decodes placed states back to slots — any
 * collision or drift silently rewrites world identity. These tests lock the
 * tables' shape, determinism and range discipline.
 */
class BlockStateSpacesTest {

    @Test
    void noteBlockKeysAreUniqueAcrossTheSpace() {
        Set<String> seen = new HashSet<>();
        for (int slot = 0; slot < BlockStateSpaces.capacity(Technique.NOTE_BLOCK); slot++) {
            assertThat(seen.add(BlockStateSpaces.variantKey(Technique.NOTE_BLOCK, slot)))
                    .as("slot %d produced a duplicate variant key", slot)
                    .isTrue();
        }
        // 22 instruments × 50 (25 notes × unpowered/powered)
        assertThat(seen).hasSize(1100);
    }

    @Test
    void noteBlockEncodingMatchesTheDocumentedFormula() {
        // slot = idx*50 + note + (powered ? 25 : 0) — the runtime decodes with this math
        assertThat(BlockStateSpaces.variantKey(Technique.NOTE_BLOCK, 0))
                .isEqualTo("instrument=harp,note=0,powered=false");
        assertThat(BlockStateSpaces.variantKey(Technique.NOTE_BLOCK, 13))
                .isEqualTo("instrument=harp,note=13,powered=false");
        assertThat(BlockStateSpaces.variantKey(Technique.NOTE_BLOCK, 25))
                .isEqualTo("instrument=harp,note=0,powered=true");
        assertThat(BlockStateSpaces.variantKey(Technique.NOTE_BLOCK, 50))
                .isEqualTo("instrument=basedrum,note=0,powered=false");
    }

    @Test
    void tripwireSpaceIsSevenBitsWithSlotZeroReserved() {
        assertThat(BlockStateSpaces.capacity(Technique.STRING)).isEqualTo(127);
        assertThatThrownBy(() -> BlockStateSpaces.variantKey(Technique.STRING, 0))
                .isInstanceOf(IllegalArgumentException.class);
        // bit layout: east=1 west=2 south=4 north=8 attached=16 disarmed=32 powered=64;
        // every allocatable slot has at least one bit set (0 is the reserved wire state)
        assertThat(BlockStateSpaces.variantKey(Technique.STRING, TRIPWIRE_ALL_CONNECTIONS))
                .isEqualTo("attached=false,disarmed=false,powered=false,"
                        + "north=true,east=true,south=true,west=true");
        assertThat(BlockStateSpaces.variantKey(Technique.STRING, TRIPWIRE_ALL_CONNECTIONS | 16 | 64))
                .isEqualTo("attached=true,disarmed=false,powered=true,"
                        + "north=true,east=true,south=true,west=true");
        assertThat(BlockStateSpaces.tripwireReservedKey())
                .isEqualTo("attached=false,disarmed=false,powered=false,"
                        + "north=false,east=false,south=false,west=false");
    }

    private static final int TRIPWIRE_ALL_CONNECTIONS = 1 | 2 | 4 | 8; // east|west|south|north

    @Test
    void chorusSpaceCoversSixPropertyBits() {
        assertThat(BlockStateSpaces.capacity(Technique.CHORUS)).isEqualTo(64);
        assertThat(BlockStateSpaces.variantKey(Technique.CHORUS, 0))
                .isEqualTo("north=false,east=false,south=false,west=false,up=false,down=false");
        // bits: up=1 down=2 north=4 east=8 south=16 west=32
        assertThat(BlockStateSpaces.variantKey(Technique.CHORUS, 1))
                .isEqualTo("north=false,east=false,south=false,west=false,up=true,down=false");
        assertThat(BlockStateSpaces.variantKey(Technique.CHORUS, 1 | 2))
                .isEqualTo("north=false,east=false,south=false,west=false,up=true,down=true");
        assertThat(BlockStateSpaces.variantKey(Technique.CHORUS, 32))
                .isEqualTo("north=false,east=false,south=false,west=true,up=false,down=false");
    }

    @Test
    void technicalBlocksAllocateNoState() {
        assertThat(BlockStateSpaces.allocatesState(Technique.TECHNICAL)).isFalse();
        assertThat(BlockStateSpaces.capacity(Technique.TECHNICAL)).isZero();
        assertThatThrownBy(() -> BlockStateSpaces.variantKey(Technique.TECHNICAL, 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hostsResolvePerTechniqueAndDemandExplicitTechnicalHosts() {
        assertThat(BlockStateSpaces.hostOf(Technique.NOTE_BLOCK, null)).isEqualTo("note_block");
        assertThat(BlockStateSpaces.hostOf(Technique.STRING, null)).isEqualTo("tripwire");
        assertThat(BlockStateSpaces.hostOf(Technique.CHORUS, null)).isEqualTo("chorus_plant");
        assertThat(BlockStateSpaces.hostOf(Technique.TECHNICAL, "mushroom_stem"))
                .isEqualTo("mushroom_stem");
        assertThatThrownBy(() -> BlockStateSpaces.hostOf(Technique.TECHNICAL, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outOfRangeSlotsAreRejectedLoudly() {
        assertThatThrownBy(() -> BlockStateSpaces.variantKey(Technique.NOTE_BLOCK, 1100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlockStateSpaces.variantKey(Technique.NOTE_BLOCK, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlockStateSpaces.variantKey(Technique.STRING, 128))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlockStateSpaces.variantKey(Technique.CHORUS, 64))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The runtime's decode side must be the exact inverse of the encode
     * formula across the whole note-block space — a drift here silently
     * rewrites placed worlds (runtime-systems §4.2).
     */
    @Test
    void noteSlotAndNotePartsRoundTripTheWholeSpace() {
        for (int slot = 0; slot < BlockStateSpaces.capacity(Technique.NOTE_BLOCK); slot++) {
            int[] parts = BlockStateSpaces.noteParts(slot);
            assertThat(BlockStateSpaces.noteSlot(parts[0], parts[1], parts[2] == 1))
                    .as("slot %d", slot)
                    .isEqualTo(slot);
            assertThat(parts[1]).isBetween(0, 24);
        }
        int[] powered = BlockStateSpaces.noteParts(25);
        assertThat(powered).containsExactly(0, 0, 1);
    }

    @Test
    void inRangeMatchesEachTechniqueSpace() {
        assertThat(BlockStateSpaces.inRange(Technique.NOTE_BLOCK, 0)).isTrue();
        assertThat(BlockStateSpaces.inRange(Technique.NOTE_BLOCK, 1099)).isTrue();
        assertThat(BlockStateSpaces.inRange(Technique.NOTE_BLOCK, 1100)).isFalse();
        assertThat(BlockStateSpaces.inRange(Technique.STRING, 1)).isTrue();
        assertThat(BlockStateSpaces.inRange(Technique.STRING, 127)).isTrue();
        assertThat(BlockStateSpaces.inRange(Technique.STRING, 0)).isFalse();
        assertThat(BlockStateSpaces.inRange(Technique.CHORUS, 63)).isTrue();
        assertThat(BlockStateSpaces.inRange(Technique.CHORUS, 64)).isFalse();
        assertThat(BlockStateSpaces.inRange(Technique.TECHNICAL, 0)).isFalse();
    }
}
