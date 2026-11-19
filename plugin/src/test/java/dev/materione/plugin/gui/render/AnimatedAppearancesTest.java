package dev.materione.plugin.gui.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.content.gui.ItemComponent;
import dev.materione.core.runtime.RuntimeGlyphs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** Which slots play an animation on the GUI's refresh clock, and when they are rewritten (M6, #9). */
class AnimatedAppearancesTest {

    private static RuntimeGlyphs registry() {
        var entries = new LinkedHashMap<NamespacedId, RuntimeGlyphs.Entry>();
        entries.put(NamespacedId.parse("sample:spark"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:spark"), null, new int[] {42000, 42001}, List.of(), null, false, false,
                null, null, null, 4));
        entries.put(NamespacedId.parse("sample:heart"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:heart"), null, new int[] {42010}, List.of(), null, false, false,
                null, null, null));
        return new RuntimeGlyphs(entries);
    }

    private static Appearance lore(String... lines) {
        return new Appearance(new SlotSource.Vanilla(Material.STONE), null, List.of(lines),
                ItemComponent.LoreMode.REPLACE, 1, null);
    }

    @Test
    void loreWithAnAnimatedGlyphGetsAPhase() {
        var shown = AnimatedAppearances.at(lore("plain", "<glyph:spark> glow"), registry(), 6);
        assertThat(shown.phase()).isNotNull();
        assertThat(shown.phase().ticks()).isEqualTo(6);
    }

    @Test
    void nameAndStillGlyphsAndPlainTextStayStatic() {
        RuntimeGlyphs glyphs = registry();
        assertThat(AnimatedAppearances.at(lore("<glyph:heart> only"), glyphs, 6).phase()).isNull();
        assertThat(AnimatedAppearances.at(lore("plain"), glyphs, 6).phase()).isNull();
        var named = new Appearance(new SlotSource.Vanilla(Material.STONE), "<g:spark> Name", List.of(),
                ItemComponent.LoreMode.REPLACE, 1, null);
        assertThat(AnimatedAppearances.at(named, glyphs, 6).phase()).isNotNull();
    }

    @Test
    void aRegistryWithoutAnimationsCostsNothing() {
        var appearance = lore("<glyph:spark>");
        assertThat(AnimatedAppearances.at(appearance, RuntimeGlyphs.empty(), 6)).isSameAs(appearance);
        assertThat(AnimatedAppearances.at(appearance, null, 6)).isSameAs(appearance);
    }

    @Test
    void aSlotIsRewrittenOnlyWhenAFrameMoves() {
        RuntimeGlyphs glyphs = registry();
        var base = lore("<glyph:spark>");
        var t0 = AnimatedAppearances.at(base, glyphs, 0);
        var t3 = AnimatedAppearances.at(base, glyphs, 3); // same frame as t0
        var t4 = AnimatedAppearances.at(base, glyphs, 4); // the next frame
        var t8 = AnimatedAppearances.at(base, glyphs, 8); // wrapped back to frame 1

        assertThat(t3).isEqualTo(t0);
        assertThat(t4).isNotEqualTo(t0);
        assertThat(t8).isEqualTo(t0);

        var writes = new ArrayList<Integer>();
        assertThat(RenderDiff.apply(Map.of(0, t0), Map.of(0, t3), (slot, appearance) -> writes.add(slot))).isZero();
        assertThat(RenderDiff.apply(Map.of(0, t0), Map.of(0, t4), (slot, appearance) -> writes.add(slot)))
                .isEqualTo(1);
    }
}
