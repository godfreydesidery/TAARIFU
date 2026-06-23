package com.taarifu.common.api.dto;

/**
 * Pagination metadata carried in {@link ApiResponse#meta()} for paged responses
 * (PRD §17, ARCHITECTURE.md §5.3).
 *
 * <p>Responsibility: tells clients how to page without re-deriving it from the payload. Produced by
 * {@code common.pagination.PageMapper} from a Spring Data {@code Page}.</p>
 *
 * @param page       zero-based index of the returned page.
 * @param size       page size actually applied (after the cap, see {@code PageRequestFactory}).
 * @param total      total number of matching elements across all pages.
 * @param totalPages total number of pages given {@code size}.
 */
public record PageMeta(
        int page,
        int size,
        long total,
        int totalPages
) {
}
