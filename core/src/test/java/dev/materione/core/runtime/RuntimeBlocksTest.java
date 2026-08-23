package dev.materione.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.blocks.BlockStateSpaces;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The state-as-identity fast path and the item↔block placement links. */
class RuntimeBlocksTest {

    private static NamespacedId id(String value) {
        return NamespacedId.of("sample", value);
    }

    private static RuntimeBlock noteBlock(String name, int slot) {
        return new RuntimeBlock(id(name), BlockStateSpaces.Technique.NOTE_BLOCK, "note_block",
                slot, List.of(), false, "keep");
    }

    @Test
    void bySlotAnswersFromPrecomputedArraysAndSkipsVanillaStates() {
        RuntimeBlocks blocks = RuntimeBlocks.builder()
                .block(noteBlock("lamp", 7))
                .block(noteBlock("crate", 1099))
                .build();
        assertThat(blocks.bySlot("note_block", 7)).hasValueSatisfying(b ->
                assertThat(b.id()).isEqualTo(id("lamp")));
        // the top slot of the space is the last allocatable index
        assertThat(blocks.bySlot("note_block", 1099)).hasValueSatisfying(b ->
                assertThat(b.id()).isEqualTo(id("crate")));
        // every unmapped state is vanilla: empty, no exception
        assertThat(blocks.bySlot("note_block", 8)).isEmpty();
        assertThat(blocks.bySlot("note_block", -1)).isEmpty();
        assertThat(blocks.bySlot("note_block", 5000)).isEmpty();
        assertThat(blocks.bySlot("unknown_host", 3)).isEmpty();
    }

    @Test
    void sparseSlotsLeaveNullHolesThatReadAsVanilla() {
        RuntimeBlocks blocks = RuntimeBlocks.builder()
                .block(noteBlock("a", 2))
                .block(noteBlock("b", 9))
                .build();
        assertThat(blocks.count()).isEqualTo(2);
        assertThat(blocks.bySlot("note_block", 5)).isEmpty();
        assertThat(blocks.bySlot("note_block", 9)).isPresent();
    }

    @Test
    void technicalBlocksAreIndexedByIdOnly() {
        RuntimeBlocks blocks = RuntimeBlocks.builder()
                .block(new RuntimeBlock(id("pedestal"), BlockStateSpaces.Technique.TECHNICAL,
                        "mushroom_stem", -1, List.of(), true, "break"))
                .build();
        assertThat(blocks.byId(id("pedestal"))).isPresent();
        assertThat(blocks.bySlot("mushroom_stem", 0)).isEmpty();
    }

    @Test
    void placementLinksResolveInBothDirections() {
        RuntimeBlocks blocks = RuntimeBlocks.builder()
                .block(new RuntimeBlock(id("lamp"), BlockStateSpaces.Technique.NOTE_BLOCK,
                        "note_block", 3, List.of(), false, "keep"))
                .placedBy(id("lamp_item"), id("lamp"))
                .build();
        assertThat(blocks.placedBy(id("lamp_item"))).hasValue(id("lamp"));
        assertThat(blocks.itemFor(id("lamp"))).hasValue(id("lamp_item"));
        assertThat(blocks.placedBy(id("other"))).isEmpty();
        assertThat(blocks.itemFor(id("other"))).isEmpty();
    }
}
