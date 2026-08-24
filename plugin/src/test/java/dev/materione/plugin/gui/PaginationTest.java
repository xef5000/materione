package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Page math for paginated menus — pure, so every edge is pinned here. */
class PaginationTest {

    @Test
    void emptyMenuHasOneEmptyPage() {
        var pagination = new Pagination(0, 45);
        assertThat(pagination.pageCount()).isOne();
        assertThat(pagination.start(0)).isZero();
        assertThat(pagination.end(0)).isZero();
        assertThat(pagination.hasNext(0)).isFalse();
        assertThat(pagination.hasPrevious(0)).isFalse();
    }

    @Test
    void exactMultipleFillsWholePages() {
        assertThat(new Pagination(90, 45).pageCount()).isEqualTo(2);
        assertThat(new Pagination(45, 45).pageCount()).isOne();
    }

    @Test
    void remainderPageCarriesTheTail() {
        var pagination = new Pagination(47, 45);
        assertThat(pagination.pageCount()).isEqualTo(2);
        assertThat(pagination.start(1)).isEqualTo(45);
        assertThat(pagination.end(1)).isEqualTo(47);
    }

    @Test
    void clampKeepsPagesInRange() {
        var pagination = new Pagination(10, 45);
        assertThat(pagination.clamp(-5)).isZero();
        assertThat(pagination.clamp(0)).isZero();
        assertThat(pagination.clamp(9)).isZero();
        assertThat(new Pagination(100, 45).clamp(3)).isEqualTo(2);
    }

    @Test
    void navigationFlagsTrackPosition() {
        var pagination = new Pagination(100, 45); // 3 pages
        assertThat(pagination.hasPrevious(0)).isFalse();
        assertThat(pagination.hasNext(0)).isTrue();
        assertThat(pagination.hasPrevious(2)).isTrue();
        assertThat(pagination.hasNext(2)).isFalse();
    }

    @Test
    void rejectsImpossibleGeometry() {
        assertThatThrownBy(() -> new Pagination(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pagination(-1, 45))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
