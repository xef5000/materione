package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.blocks.BlockStateSpaces.Technique;
import dev.materione.core.runtime.RuntimeBlock;
import dev.materione.core.runtime.RuntimeBlocks;
import dev.materione.core.runtime.RuntimeFurniture;
import dev.materione.core.runtime.RuntimeFurnitures;
import dev.materione.core.runtime.RuntimeItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The browser's row model: items, then blocks, then furniture per pack,
 * each id-sorted, with placement links resolved — the contract that makes
 * blocks/furniture visible next to items in {@code /materione browser}.
 */
class ContentBrowserEntriesTest {

    @Test
    void packRowsAreItemsThenBlocksThenFurnitureEachIdSorted() {
        var ruby = item("sample", "ruby");
        var axe = item("sample", "obsidian_axe");
        var stairs = block("sample", "marble_stairs");
        var slab = block("sample", "marble_slab");
        var chair = furniture("sample", "chair");
        var blocks = RuntimeBlocks.builder().block(stairs).block(slab).build();
        var furnitures = RuntimeFurnitures.builder().furniture(chair).build();

        var rows = ContentBrowser.entriesFor(
                "sample", List.of(ruby, axe), blocks, furnitures);

        assertThat(rows).hasSize(5);
        assertThat(rows.get(0)).isEqualTo(new ContentBrowser.ItemEntry(axe));
        assertThat(rows.get(1)).isEqualTo(new ContentBrowser.ItemEntry(ruby));
        assertThat(rows.get(2)).isEqualTo(new ContentBrowser.BlockEntry(slab, java.util.Optional.empty()));
        assertThat(rows.get(3)).isEqualTo(new ContentBrowser.BlockEntry(stairs, java.util.Optional.empty()));
        assertThat(rows.get(4)).isEqualTo(new ContentBrowser.FurnitureEntry(chair, java.util.Optional.empty()));
    }

    @Test
    void placementLinksResolveFromTheSameSnapshot() {
        var ruby = item("sample", "ruby");
        var lamp = block("sample", "amethyst_lamp");
        var chair = furniture("sample", "chair");
        var blocks = RuntimeBlocks.builder()
                .block(lamp).placedBy(ruby.id(), lamp.id()).build();
        var furnitures = RuntimeFurnitures.builder()
                .furniture(chair).placedBy(ruby.id(), chair.id()).build();

        var rows = ContentBrowser.entriesFor(
                "sample", List.of(ruby), blocks, furnitures);

        assertThat(rows).containsExactly(
                new ContentBrowser.ItemEntry(ruby),
                new ContentBrowser.BlockEntry(lamp, java.util.Optional.of(ruby.id())),
                new ContentBrowser.FurnitureEntry(chair, java.util.Optional.of(ruby.id())));
    }

    @Test
    void otherPacksRowsAreFilteredOut() {
        var corpusItem = item("corpus", "gem_red");
        var corpusBlock = block("corpus", "block_red");
        var blocks = RuntimeBlocks.builder().block(corpusBlock).build();

        var rows = ContentBrowser.entriesFor(
                "sample", List.of(corpusItem), blocks, null);

        assertThat(rows).isEmpty();
    }

    private static RuntimeItem item(String namespace, String value) {
        return new RuntimeItem(
                NamespacedId.of(namespace, value), "PAPER", null, "Name", List.of(), "hash");
    }

    private static RuntimeBlock block(String namespace, String value) {
        return new RuntimeBlock(
                NamespacedId.of(namespace, value), Technique.NOTE_BLOCK, "note_block",
                0, List.of(), false, "keep");
    }

    private static RuntimeFurniture furniture(String namespace, String value) {
        return new RuntimeFurniture(
                NamespacedId.of(namespace, value), namespace + ":" + value + "_model", "Name",
                90, List.of(new RuntimeFurniture.Hitbox("interaction", new double[] {0, 0, 0}, 1, 1)),
                List.of(new double[] {0, 0.3, 0}), List.of(), 0, null, List.of());
    }
}
