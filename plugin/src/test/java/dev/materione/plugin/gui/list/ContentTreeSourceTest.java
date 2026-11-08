package dev.materione.plugin.gui.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ListSource;
import dev.materione.api.logic.ParamReader;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.content.gui.list.ListSearch;
import dev.materione.core.runtime.RuntimeContentIndex;
import dev.materione.core.runtime.RuntimeContentIndex.Entry;
import dev.materione.core.runtime.RuntimeContentIndex.Type;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.gui.render.RenderReporter;
import dev.materione.plugin.logic.support.ItemCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@code content} list source (runtime-systems §8.3, logic.md §6.7): packs, then folders, then items, one location
 * at a time from a GUI argument, and a flat list of every item while a session search holds text. Pure: the index is a
 * view of the shadow, and a stack size is read through a seam.
 */
class ContentTreeSourceTest {

    private static Entry item(String id, String name, String... folders) {
        return new Entry(NamespacedId.parse(id), Type.ITEM, name, List.of(folders));
    }

    private static final List<Entry> ENTRIES = List.of(
            item("shop:ruby", "Ruby", "gems"),
            item("shop:raw_ruby", "Raw Ruby", "gems", "raw"),
            item("shop:iron_sword", "Iron Sword", "tools"),
            item("shop:coin", "Coin"),
            new Entry(NamespacedId.parse("shop:lamp"), Type.BLOCK, "Lamp", "<yellow>Lamp", List.of("gems")),
            item("zoo:lion", "Lion", "animals"));

    private static ItemCatalog catalog() {
        var builder = RuntimeRegistries.builder();
        for (Entry entry : ENTRIES) {
            String material = entry.id().value().equals("iron_sword") ? "DIAMOND_SWORD" : "PAPER";
            builder.item(new RuntimeItem(entry.id(), material, null, null, List.of(), "hash"));
        }
        RuntimeRegistries registries = builder.view(RuntimeContentIndex.KEY, RuntimeContentIndex.of(ENTRIES)).build();
        return () -> Optional.of(registries);
    }

    private final ContentTreeSource source = new ContentTreeSource(catalog(),
            material -> material.equals("DIAMOND_SWORD") ? 1 : 64);

    private static MapLogicContext window(Object path, Object search) {
        var args = new LinkedHashMap<String, Object>();
        if (path != null) {
            args.put("path", path);
        }
        var session = new LinkedHashMap<String, Object>();
        if (search != null) {
            session.put("search", search);
        }
        return new MapLogicContext(Map.of("gui", Map.of("args", args, "session", session)));
    }

    private List<Map<String, Object>> read(Object path, Object search) {
        return source.entries(source.parse(ParamReader.of(Map.of("arg", "path", "search", "search"))),
                window(path, search));
    }

    private static List<Object> field(List<Map<String, Object>> rows, String name) {
        return rows.stream().map(row -> row.get(name)).toList();
    }

    @Test
    void itIsRegisteredAsContentAndSelfDescribesItsParametersAndItsRows() {
        var description = source.describe();

        assertThat(source.id()).isEqualTo("content");
        assertThat(description.cached()).isFalse();
        assertThat(description.shorthand()).isNull();
        assertThat(description.self().parameters()).extracting(parameter -> parameter.id())
                .containsExactly("arg", "search");
        assertThat(description.entry()).extracting(variable -> variable.path()).containsExactlyInAnyOrder(
                "entry.kind", "entry.name", "entry.label", "entry.path", "entry.count", "entry.id", "entry.type",
                "entry.stack", "entry.detail", "entry.search");
        assertThat(BuiltinListSources.all(catalog())).extracting(ListSource::id).contains("content");
    }

    @Test
    void theRootListsThePacksAndAnEmptyArgumentIsTheRootToo() {
        var root = read(null, null);

        assertThat(field(root, "kind")).containsExactly("pack", "pack");
        assertThat(field(root, "name")).containsExactly("shop", "zoo");
        assertThat(field(root, "path")).containsExactly("shop", "zoo");
        assertThat(field(root, "detail")).containsExactly("5 items", "1 item");
        assertThat(read("", null)).isEqualTo(root);
    }

    @Test
    void aLocationListsFoldersThenItemsWithWhatAClickNeeds() {
        var shop = read("shop", null);
        assertThat(field(shop, "kind")).containsExactly("folder", "folder", "item");
        assertThat(field(shop, "path")).containsExactly("shop/gems", "shop/tools", "");

        var gems = read("shop/gems", null);
        assertThat(field(gems, "name")).containsExactly("raw", "Lamp", "Ruby");
        var lamp = gems.get(1);
        assertThat(lamp).containsEntry("kind", "item").containsEntry("type", "block")
                .containsEntry("id", "shop:lamp").containsEntry("label", "<yellow>Lamp")
                .containsEntry("detail", "shop:lamp").containsEntry("path", "").containsEntry("count", 1)
                .containsEntry("search", "shop:lamp lamp");
        assertThat(gems.get(2)).containsEntry("type", "item").containsEntry("id", "shop:ruby")
                .containsEntry("stack", 64);
    }

    @Test
    void theStackSizeIsTheItemsOwnAndAFolderIsOne() {
        assertThat(read("shop/tools", null).get(0)).containsEntry("stack", 1);
        assertThat(read("shop", null).get(0)).containsEntry("stack", 1).containsEntry("id", "");
        // an item that is no longer live, or whose material cannot say, is a full stack
        var unknown = new ContentTreeSource(() -> Optional.of(RuntimeRegistries.builder()
                .view(RuntimeContentIndex.KEY, RuntimeContentIndex.of(ENTRIES)).build()), material -> 1);
        var rows = unknown.entries(unknown.parse(ParamReader.of(Map.of())), window("shop/tools", null));
        assertThat(rows.get(0)).containsEntry("stack", ContentTreeSource.DEFAULT_STACK);
    }

    @Test
    void whileASearchHoldsTextEveryItemOfEveryPackIsListedFlatForTheListToFilter() {
        var searching = read("shop/gems", "ruby");

        assertThat(field(searching, "name")).containsExactly("Coin", "Iron Sword", "Lamp", "Lion", "Raw Ruby", "Ruby");
        assertThat(field(searching, "kind")).containsOnly("item");
        // blank text, or no search parameter, is the tree
        assertThat(field(read("shop/gems", "  "), "name")).containsExactly("raw", "Lamp", "Ruby");
        var noSearch = source.entries(source.parse(ParamReader.of(Map.of())), window("shop/gems", "ruby"));
        assertThat(field(noSearch, "name")).containsExactly("raw", "Lamp", "Ruby");
    }

    @Test
    void theListsOwnSearchKeepsTheItemsWhoseIdAndNameHoldEveryWord() {
        var all = read(null, "x");
        var search = new ListSearch("search", List.of("search"));
        var layer = new CompiledListLayer("entries", 0, List.of(0, 1, 2, 3), null, false, null, null, search, null, null);
        var context = window(null, "RUBY raw");

        var kept = ListEntries.arrange(layer, context, all, RenderReporter.SILENT, NamespacedId.of("materione", "browser"));

        assertThat(field(kept, "name")).containsExactly("Raw Ruby");
        // the id counts too, and the order of the words does not
        assertThat(field(ListEntries.arrange(layer, window(null, "ruby SHOP:raw"), all, RenderReporter.SILENT,
                NamespacedId.of("materione", "browser")), "name")).containsExactly("Raw Ruby");
    }

    @Test
    void aLocationThatNamesNothingLiveIsEmptyAndNothingLiveYetIsEmpty() {
        assertThat(read("shop/gone", null)).isEmpty();
        var none = new ContentTreeSource(ItemCatalog.none());
        assertThat(none.entries(none.parse(ParamReader.of(Map.of())), window(null, null))).isEmpty();
    }

    @Test
    void anArgumentOrVariableNameThatIsNotAPlainNameIsRefusedAtLoad() {
        assertThatThrownBy(() -> source.parse(ParamReader.of(Map.of("arg", "gui.args"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("arg");
        assertThatThrownBy(() -> source.parse(ParamReader.of(Map.of("search", "Not Plain"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("search");
        assertThat(source.parse(ParamReader.of(Map.of()))).isEqualTo(new ContentTreeSource.Params("path", null));
    }
}
