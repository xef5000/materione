package dev.materione.plugin.gui.browse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.blocks.BlockStateSpaces.Technique;
import dev.materione.core.runtime.RuntimeBlock;
import dev.materione.core.runtime.RuntimeBlocks;
import dev.materione.core.runtime.RuntimeFurniture;
import dev.materione.core.runtime.RuntimeFurnitures;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.gui.Pagination;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The browser's pure model (M6): categories derived from the registries, the search, and the paging the window uses.
 */
class BrowseCatalogTest {

    @Test
    void anItemWithoutACategoryIsFiledUnderItsPack() {
        var registries = registries(List.of(item("shop", "ruby", null, "Ruby")), null, null);

        var rows = BrowseCatalog.build(registries);

        assertThat(rows).containsExactly(new BrowseEntry(BrowseEntry.Kind.ITEM,
                NamespacedId.of("shop", "ruby"), "Ruby", "shop", NamespacedId.of("shop", "ruby")));
    }

    @Test
    void anAuthoredCategoryFilesTheItemAndCaseDoesNotSplitGroups() {
        var rows = BrowseCatalog.build(registries(List.of(
                item("shop", "ruby", "Gems", "Ruby"),
                item("shop", "axe", "gems", "Axe"),
                item("shop", "tool", null, "Tool")), null, null));

        var categories = BrowseCatalog.categories(rows);

        assertThat(categories).extracting(BrowseCatalog.Category::key).containsExactly("gems", "shop");
        // the label is the first spelling in sort order, so one group never shows two labels
        assertThat(categories.get(0).label()).isEqualToIgnoringCase("gems");
        assertThat(categories.get(0).count()).isEqualTo(2);
        assertThat(categories.get(1).count()).isEqualTo(1);
    }

    @Test
    void aBlankCategoryFallsBackToThePack() {
        var rows = BrowseCatalog.build(registries(List.of(item("shop", "ruby", "   ", "Ruby")), null, null));

        assertThat(rows.get(0).category()).isEqualTo("shop");
    }

    @Test
    void aBlockAndAFurnitureAreFiledWithTheirPlacementItem() {
        var lamp = block("world", "amethyst_lamp");
        var chair = furniture("world", "chair");
        var ruby = item("shop", "ruby", "Gems", "Ruby");
        var blocks = RuntimeBlocks.builder().block(lamp).placedBy(ruby.id(), lamp.id()).build();
        var furnitures = RuntimeFurnitures.builder().furniture(chair).build();

        var rows = BrowseCatalog.build(registries(List.of(ruby), blocks, furnitures));

        assertThat(rows).contains(
                new BrowseEntry(BrowseEntry.Kind.BLOCK, lamp.id(), "Amethyst Lamp", "Gems", ruby.id()),
                new BrowseEntry(BrowseEntry.Kind.FURNITURE, chair.id(), "Chair", "world", null));
    }

    @Test
    void syntheticPlacementItemsAreNotRowsOfTheirOwn() {
        var synthetic = new RuntimeItem(NamespacedId.of("world", "workbench"), "PAPER",
                null, "Workbench", List.of(), "hash", null, true, null);

        var rows = BrowseCatalog.build(registries(List.of(synthetic), null, null));

        assertThat(rows).isEmpty();
    }

    @Test
    void rowsSortByCategoryThenNameCaseInsensitively() {
        var rows = BrowseCatalog.build(registries(List.of(
                item("shop", "zebra", "Gems", "zebra gem"),
                item("shop", "apple", "Gems", "Apple"),
                item("shop", "hat", "Armour", "Hat")), null, null));

        assertThat(rows).extracting(entry -> entry.id().value())
                .containsExactly("hat", "apple", "zebra");
    }

    @Test
    void richTextIsRemovedFromNamesAndAMissingNameIsHumanised() {
        assertThat(BrowseCatalog.plainName("<red>Ruby</red> &lBlade", NamespacedId.of("shop", "ruby_blade")))
                .isEqualTo("Ruby Blade");
        assertThat(BrowseCatalog.plainName(null, NamespacedId.of("shop", "amethyst_lamp")))
                .isEqualTo("Amethyst Lamp");
        assertThat(BrowseCatalog.plainName("<red></red>", NamespacedId.of("shop", "amethyst_lamp")))
                .isEqualTo("Amethyst Lamp");
    }

    @Test
    void aCategoryIsRepresentedByItsFirstItem() {
        var rows = BrowseCatalog.build(registries(List.of(
                item("shop", "apple", "Food", "Apple"),
                item("shop", "steak", "Food", "Steak")), null, null));

        var food = BrowseCatalog.categories(rows).get(0);

        assertThat(food.icon()).isEqualTo(NamespacedId.of("shop", "apple"));
        assertThat(BrowseCatalog.inCategory(rows, "food")).hasSize(2);
        assertThat(BrowseCatalog.inCategory(rows, "nothing")).isEmpty();
    }

    @Test
    void searchMatchesIdAndNameCaseInsensitively() {
        var rows = BrowseCatalog.build(registries(List.of(
                item("shop", "ruby_sword", null, "Blade of Fire"),
                item("shop", "axe", null, "RUBY Axe"),
                item("shop", "shield", null, "Shield")), null, null));

        assertThat(names(BrowseCatalog.search(rows, "RUBY"))).containsExactlyInAnyOrder("Blade of Fire", "RUBY Axe");
        assertThat(names(BrowseCatalog.search(rows, "blade"))).containsExactly("Blade of Fire");
        assertThat(names(BrowseCatalog.search(rows, "shop:shield"))).containsExactly("Shield");
    }

    @Test
    void everyWordOfASearchMustMatch() {
        var rows = BrowseCatalog.build(registries(List.of(
                item("shop", "ruby_sword", null, "Ruby Sword"),
                item("shop", "ruby_dagger", null, "Ruby Dagger")), null, null));

        assertThat(names(BrowseCatalog.search(rows, "ruby sword"))).containsExactly("Ruby Sword");
        assertThat(BrowseCatalog.search(rows, "ruby  sword  ")).hasSize(1);
        assertThat(BrowseCatalog.search(rows, "ruby emerald")).isEmpty();
    }

    @Test
    void aBlankSearchMatchesEveryRow() {
        var rows = BrowseCatalog.build(registries(List.of(item("shop", "ruby", null, "Ruby")), null, null));

        assertThat(BrowseCatalog.search(rows, null)).isEqualTo(rows);
        assertThat(BrowseCatalog.search(rows, "   ")).isEqualTo(rows);
        assertThat(BrowseCatalog.words("  Ruby   SWORD ")).containsExactly("ruby", "sword");
    }

    @Test
    void aLongCategoryPagesFortyFiveRowsAtATime() {
        var items = new java.util.ArrayList<RuntimeItem>();
        for (int i = 0; i < 50; i++) {
            items.add(item("shop", "gem_" + i, "Gems", "Gem " + i));
        }
        var rows = BrowseCatalog.inCategory(BrowseCatalog.build(registries(items, null, null)), "gems");

        var pages = new Pagination(rows.size(), BrowseGui.PER_PAGE);

        assertThat(pages.pageCount()).isEqualTo(2);
        assertThat(pages.end(0) - pages.start(0)).isEqualTo(45);
        assertThat(pages.end(1) - pages.start(1)).isEqualTo(5);
    }

    @Test
    void theOpenAndTakeNodesAreTheDocumentedOnes() {
        assertThat(BrowseAccess.nodes()).containsExactly("materione.browse", "materione.browse.get");
        assertThat(BrowseAccess.TAKE_DENIED).contains("materione.browse.get");
        assertThat(BrowseAccess.OPEN_DENIED).contains("materione.browse");
    }

    private static List<String> names(List<BrowseEntry> rows) {
        return rows.stream().map(BrowseEntry::name).toList();
    }

    private static RuntimeRegistries registries(
            List<RuntimeItem> items, RuntimeBlocks blocks, RuntimeFurnitures furnitures) {
        var builder = RuntimeRegistries.builder();
        items.forEach(builder::item);
        if (blocks != null) {
            builder.view(RuntimeBlocks.KEY, blocks);
        }
        if (furnitures != null) {
            builder.view(RuntimeFurnitures.KEY, furnitures);
        }
        return builder.build();
    }

    private static RuntimeItem item(String namespace, String value, String category, String displayName) {
        return new RuntimeItem(NamespacedId.of(namespace, value), "PAPER", null, displayName, List.of(), "hash",
                null, false, null, List.of(), category);
    }

    private static RuntimeBlock block(String namespace, String value) {
        return new RuntimeBlock(NamespacedId.of(namespace, value), Technique.NOTE_BLOCK, "note_block",
                0, List.of(), false, "keep");
    }

    private static RuntimeFurniture furniture(String namespace, String value) {
        return new RuntimeFurniture(NamespacedId.of(namespace, value), namespace + ":" + value + "_model", null,
                90, List.of(new RuntimeFurniture.Hitbox("interaction", new double[] {0, 0, 0}, 1, 1)),
                List.of(new double[] {0, 0.3, 0}), List.of(), 0, null, List.of());
    }
}
