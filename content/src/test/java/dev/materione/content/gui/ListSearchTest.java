package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.content.gui.list.ListSearch;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A list's {@code search:} shape (logic.md §28.21): what it accepts, what it rejects, and its defaults. */
class ListSearchTest {

    @Test
    void theFieldsAloneReadTheDefaultSessionVariable() {
        ListSearch search = ListSearch.parse(Map.of("fields", List.of("name")));

        assertThat(search.var()).isEqualTo("search");
        assertThat(search.fields()).containsExactly("name");
    }

    @Test
    void aNamedVariableAndSeveralFieldsParse() {
        ListSearch search = ListSearch.parse(Map.of("var", "query", "fields", List.of("name", "lore")));

        assertThat(search.var()).isEqualTo("query");
        assertThat(search.fields()).containsExactly("name", "lore");
    }

    @Test
    void aSingleFieldNamedAsText() {
        assertThat(ListSearch.parse(Map.of("fields", "name")).fields()).containsExactly("name");
    }

    @Test
    void aSearchThatIsNotAMappingIsRejected() {
        assertThatThrownBy(() -> ListSearch.parse("name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapping");
    }

    @Test
    void anUnknownKeyIsRejectedByName() {
        assertThatThrownBy(() -> ListSearch.parse(Map.of("fields", List.of("name"), "mode", "fuzzy")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'mode'");
    }

    @Test
    void aSearchNamingNoFieldIsRejected() {
        assertThatThrownBy(() -> ListSearch.parse(Map.of("fields", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ListSearch.parse(Map.of("fields", List.of(3))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ListSearch.parse(Map.of("var", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVariableThatIsNotAPlainNameIsRejected() {
        assertThatThrownBy(() -> ListSearch.parse(Map.of("var", "session.search", "fields", List.of("name"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain name");
    }
}
