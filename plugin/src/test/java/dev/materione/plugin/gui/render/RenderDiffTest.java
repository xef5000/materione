package dev.materione.plugin.gui.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RenderDiffTest {

    private static Appearance barrier(String reason) {
        return Appearance.of(new SlotSource.Barrier(reason), 1);
    }

    @Test
    void onlyTheChangedSlotsAreWrittenInSlotOrderAndARemovedOneIsCleared() {
        var shown = Map.of(0, barrier("a"), 1, barrier("b"));
        var next = Map.of(1, barrier("c"), 2, barrier("d"));
        var writes = new ArrayList<String>();

        int written = RenderDiff.apply(shown, next, (slot, appearance) -> writes.add(
                slot + "=" + (appearance == null ? "clear" : ((SlotSource.Barrier) appearance.source()).reason())));

        assertThat(written).isEqualTo(3);
        assertThat(writes).containsExactly("0=clear", "1=c", "2=d");
    }

    @Test
    void anUnchangedRenderWritesNothing() {
        var shown = Map.of(0, barrier("a"));
        var writes = new ArrayList<Integer>();

        assertThat(RenderDiff.apply(shown, Map.of(0, barrier("a")), (slot, appearance) -> writes.add(slot)))
                .isZero();
        assertThat(writes).isEmpty();
    }

    @Test
    void aChangedOverrideIsAChangedSlotEvenWhenTheSourceIsTheSame() {
        var source = new SlotSource.Barrier("same");
        var before = Appearance.of(source, 1);
        var after = new Appearance(source, "Renamed", List.of(), dev.materione.content.gui.ItemComponent.LoreMode.REPLACE,
                1, null);
        var writes = new ArrayList<Integer>();

        RenderDiff.apply(Map.of(4, before), Map.of(4, after), (slot, appearance) -> writes.add(slot));

        assertThat(writes).containsExactly(4);
    }
}
