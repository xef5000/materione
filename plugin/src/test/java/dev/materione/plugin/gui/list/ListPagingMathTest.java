package dev.materione.plugin.gui.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The paging arithmetic and the stored page state (logic.md §6.7). */
class ListPagingMathTest {

    @Test
    void aListHasAtLeastOnePageAndAFullPageHoldsItsSlots() {
        assertThat(ListPager.countOf(0, 3)).isEqualTo(1);
        assertThat(ListPager.countOf(3, 3)).isEqualTo(1);
        assertThat(ListPager.countOf(4, 3)).isEqualTo(2);
        assertThat(ListPager.countOf(9, 3)).isEqualTo(3);
        assertThatThrownBy(() -> ListPager.countOf(4, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPageNumberIsClampedIntoThePagesThatExist() {
        assertThat(ListPager.clamp(0, 2)).isEqualTo(1);
        assertThat(ListPager.clamp(2, 2)).isEqualTo(2);
        assertThat(ListPager.clamp(9, 2)).isEqualTo(2);
    }

    @Test
    void aPageHoldsItsEntriesAndAPageBeyondTheEndIsEmpty() {
        var entries = List.of("a", "b", "c", "d", "e");

        assertThat(ListPager.pageOf(entries, 1, 3)).containsExactly("a", "b", "c");
        assertThat(ListPager.pageOf(entries, 2, 3)).containsExactly("d", "e");
        assertThat(ListPager.pageOf(entries, 3, 3)).isEmpty();
        assertThat(ListPager.pageOf(List.<String>of(), 1, 3)).isEmpty();
    }

    @Test
    void thePageStateIsTheSessionsMapAndAMalformedValueIsTheFirstPage() {
        var page = new ListPage(2, 5, 41);

        assertThat(page.toMap()).isEqualTo(Map.of("number", 2, "count", 5, "size", 41));
        assertThat(ListPage.of(page.toMap())).isEqualTo(page);
        assertThat(ListPage.of(null)).isEqualTo(ListPage.FIRST);
        assertThat(ListPage.of("two")).isEqualTo(ListPage.FIRST);
        assertThat(ListPage.of(Map.of("number", 9, "count", 3))).isEqualTo(new ListPage(3, 3, 0));
        assertThatThrownBy(() -> new ListPage(4, 3, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
