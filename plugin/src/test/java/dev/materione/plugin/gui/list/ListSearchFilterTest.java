package dev.materione.plugin.gui.list;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.content.gui.list.ListSearch;
import dev.materione.plugin.gui.render.RenderReporter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The search seam of {@link ListEntries#arrange} (logic.md §28.21, #220): the session text keeps matching entries. */
class ListSearchFilterTest {

    private static final NamespacedId GUI = NamespacedId.of("guilogic", "search");
    private static final RenderReporter SILENT = RenderReporter.SILENT;

    private final List<Map<String, Object>> entries = List.of(
            Map.of("name", "Diamond"),
            Map.of("name", "Gold ingot"),
            Map.of("name", "diamond block"),
            Map.of("name", "Iron"));

    private static CompiledListLayer layer(ListSearch search) {
        return new CompiledListLayer("items", 0, List.of(10, 11, 12, 13), null, false, null, null, search, null, null);
    }

    private static MapLogicContext session(Object search) {
        Map<String, Object> session = search == null ? Map.of() : Map.of("search", search);
        return new MapLogicContext(Map.of("gui", Map.of("session", session)));
    }

    private List<Object> names(CompiledListLayer layer, MapLogicContext context) {
        return ListEntries.arrange(layer, context, entries, SILENT, GUI).stream()
                .map(entry -> entry.get("name")).toList();
    }

    @Test
    void aSessionSearchKeepsTheEntriesWhoseFieldContainsItIgnoringCase() {
        assertThat(names(layer(new ListSearch("search", List.of("name"))), session("DIA")))
                .containsExactly("Diamond", "diamond block");
    }

    @Test
    void anUnsetOrBlankSearchKeepsEveryEntry() {
        ListSearch search = new ListSearch("search", List.of("name"));

        assertThat(names(layer(search), session(null))).hasSize(4);
        assertThat(names(layer(search), session("   "))).hasSize(4);
    }

    @Test
    void aListWithoutASearchIgnoresTheSessionText() {
        assertThat(names(layer(null), session("iron"))).hasSize(4);
    }

    @Test
    void aSearchReadsTheVariableItNames() {
        ListSearch search = new ListSearch("query", List.of("name"));
        var context = new MapLogicContext(Map.of("gui", Map.of("session", Map.of("search", "iron", "query", "gold"))));

        assertThat(names(layer(search), context)).containsExactly("Gold ingot");
    }

    @Test
    void anEntryWithNoValueForTheSearchedFieldIsNotAMatch() {
        var withoutName = List.<Map<String, Object>>of(Map.of("lore", "diamond"));
        var kept = ListEntries.arrange(layer(new ListSearch("search", List.of("name"))), session("diamond"),
                withoutName, SILENT, GUI);

        assertThat(kept).isEmpty();
    }

    @Test
    void theSearchMatchesEveryListedField() {
        var rows = List.<Map<String, Object>>of(Map.of("name", "Stone", "lore", "Mined with a diamond pick"));
        var kept = ListEntries.arrange(layer(new ListSearch("search", List.of("name", "lore"))), session("diamond"),
                rows, SILENT, GUI);

        assertThat(kept).hasSize(1);
    }
}
