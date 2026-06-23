package com.taarifu.common;

import com.taarifu.common.pagination.PageRequestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PageRequestFactory} (FOUNDATION-SCOPE.md §6, CLAUDE.md §10).
 *
 * <p>Responsibility: proves the server-side paging caps and defaults that protect a national-scale,
 * feature-phone-served system from unbounded page sizes (PRD §15) — the key being that the {@code size}
 * cap is enforced regardless of what the client requests.</p>
 */
class PageRequestFactoryTest {

    private final PageRequestFactory factory = new PageRequestFactory();

    @Test
    void appliesDefaultsWhenParamsAbsent() {
        Pageable pageable = factory.of(null, null, null);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_SIZE);
        assertThat(pageable.getSort().isSorted()).isFalse();
    }

    @Test
    void capsOversizedPageSize() {
        Pageable pageable = factory.of(0, 5_000, null);

        // The client asked for 5000; the server caps it at 100 (PRD §15 data-budget/DoS guard).
        assertThat(pageable.getPageSize()).isEqualTo(PageRequestFactory.MAX_SIZE);
    }

    @Test
    void clampsNegativePageToZero() {
        Pageable pageable = factory.of(-3, 20, null);
        assertThat(pageable.getPageNumber()).isZero();
    }

    @Test
    void parsesDescendingSort() {
        Pageable pageable = factory.of(0, 20, "name,desc");

        Sort.Order order = pageable.getSort().getOrderFor("name");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void defaultsToAscendingWhenDirectionOmitted() {
        Pageable pageable = factory.of(0, 20, "name");

        Sort.Order order = pageable.getSort().getOrderFor("name");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }
}
