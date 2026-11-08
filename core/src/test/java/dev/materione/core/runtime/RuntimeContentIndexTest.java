package dev.materione.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeContentIndex.Entry;
import dev.materione.core.runtime.RuntimeContentIndex.Kind;
import dev.materione.core.runtime.RuntimeContentIndex.Node;
import dev.materione.core.runtime.RuntimeContentIndex.Type;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The content tree a browser walks (runtime-systems §8.3): packs, the folders inside them, then entries. */
class RuntimeContentIndexTest {

    private static Entry item(String id, String name, String... folders) {
        return new Entry(NamespacedId.parse(id), Type.ITEM, name, List.of(folders));
    }

    private final RuntimeContentIndex index = RuntimeContentIndex.of(List.of(
            item("shop:ruby", "Ruby", "gems"),
            item("shop:emerald", "Emerald", "gems"),
            item("shop:raw_ruby", "Raw Ruby", "gems", "raw"),
            item("shop:iron_sword", "Iron Sword", "tools"),
            item("shop:coin", "Coin"),
            new Entry(NamespacedId.parse("shop:amethyst_lamp"), Type.BLOCK, "Amethyst Lamp", List.of("gems")),
            item("zoo:lion", "Lion", "animals", "big"),
            item("zoo:cat", "Cat", "animals")));

    private static List<String> names(List<Node> nodes) {
        return nodes.stream().map(Node::name).toList();
    }

    @Test
    void theRootListsThePacksThatHoldContentWithTheirCounts() {
        List<Node> root = index.children("");

        assertThat(names(root)).containsExactly("shop", "zoo");
        assertThat(root).extracting(Node::kind).containsOnly(Kind.PACK);
        assertThat(root).extracting(Node::count).containsExactly(6, 2);
        assertThat(root).extracting(Node::path).containsExactly("shop", "zoo");
    }

    @Test
    void aPackListsItsFoldersFirstThenItsOwnEntries() {
        List<Node> shop = index.children("shop");

        assertThat(names(shop)).containsExactly("gems", "tools", "Coin");
        assertThat(shop).extracting(Node::kind).containsExactly(Kind.FOLDER, Kind.FOLDER, Kind.ENTRY);
        assertThat(shop).extracting(Node::path).containsExactly("shop/gems", "shop/tools", "");
        // a folder counts every depth under it: gems holds ruby, emerald, the lamp and raw/raw_ruby
        assertThat(shop).extracting(Node::count).containsExactly(4, 1, 1);
    }

    @Test
    void aFolderListsItsSubFoldersThenEntriesSortedByNameThenId() {
        List<Node> gems = index.children("shop/gems");

        assertThat(names(gems)).containsExactly("raw", "Amethyst Lamp", "Emerald", "Ruby");
        assertThat(gems).extracting(Node::kind).containsExactly(Kind.FOLDER, Kind.ENTRY, Kind.ENTRY, Kind.ENTRY);
        assertThat(gems.get(0).path()).isEqualTo("shop/gems/raw");
        assertThat(index.children("shop/gems/raw")).singleElement().satisfies(node -> {
            assertThat(node.kind()).isEqualTo(Kind.ENTRY);
            assertThat(node.entry().id()).isEqualTo(NamespacedId.parse("shop:raw_ruby"));
        });
    }

    @Test
    void foldersSortIgnoringCaseAndAnEntryOfAnotherPackNeverLeaksIn() {
        assertThat(names(index.children("zoo"))).containsExactly("animals");
        assertThat(names(index.children("zoo/animals"))).containsExactly("big", "Cat");
        assertThat(index.children("zoo/animals").get(1).entry().id()).isEqualTo(NamespacedId.parse("zoo:cat"));
    }

    @Test
    void aLocationThatNamesNothingLiveIsEmpty() {
        assertThat(index.children("missing")).isEmpty();
        assertThat(index.children("shop/missing")).isEmpty();
        assertThat(index.children("shop/gems/raw/deeper")).isEmpty();
        assertThat(RuntimeContentIndex.empty().children("")).isEmpty();
        assertThat(RuntimeContentIndex.empty().size()).isZero();
    }

    @Test
    void anEntryKnowsItsPackItsLocationAndWhatASearchMatches() {
        Entry raw = item("shop:raw_ruby", "Raw Ruby", "gems", "raw");

        assertThat(raw.pack()).isEqualTo("shop");
        assertThat(raw.location()).isEqualTo("shop/gems/raw");
        assertThat(item("shop:coin", "Coin").location()).isEqualTo("shop");
        assertThat(raw.searchKey()).isEqualTo("shop:raw_ruby raw ruby");
    }

    @Test
    void parentsAndSplitsOfALocation() {
        assertThat(RuntimeContentIndex.parent("shop/gems/raw")).isEqualTo("shop/gems");
        assertThat(RuntimeContentIndex.parent("shop")).isEmpty();
        assertThat(RuntimeContentIndex.parent("")).isEmpty();
        assertThat(RuntimeContentIndex.parent(null)).isEmpty();
        assertThat(RuntimeContentIndex.split("shop//gems/")).containsExactly("shop", "gems");
        assertThat(RuntimeContentIndex.split(" ")).isEmpty();
    }

    @Test
    void plainNamesDropFormattingAndFallBackToTheHumanisedId() {
        NamespacedId id = NamespacedId.parse("shop:amethyst_lamp");

        assertThat(RuntimeContentIndex.plainName("<gradient:red:blue>Amethyst</gradient> &lLamp", id))
                .isEqualTo("Amethyst Lamp");
        assertThat(RuntimeContentIndex.plainName(null, id)).isEqualTo("Amethyst Lamp");
        assertThat(RuntimeContentIndex.plainName("  <red></red> ", id)).isEqualTo("Amethyst Lamp");
        assertThat(RuntimeContentIndex.plainName(null, NamespacedId.parse("shop:gui/main/iron_sword")))
                .isEqualTo("Iron Sword");
    }
}
