package com.taarifu.common.pagination;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Parses client paging/sorting parameters into a safe Spring {@link Pageable}
 * (PRD §17, ARCHITECTURE.md §5.3).
 *
 * <p>Responsibility: turns raw {@code page}/{@code size}/{@code sort} query params into a bounded
 * {@link Pageable}, applying the platform defaults and caps in one place so every endpoint behaves
 * identically (DRY).</p>
 *
 * <p>WHY the size cap is enforced server-side (not trusted from the client): an unbounded page size
 * is a denial-of-service and data-budget hazard for a national-scale, feature-phone-served system
 * (PRD §15). Defaults: {@code page=0}, {@code size=20}, hard cap {@code size≤100}.</p>
 */
@Component
public class PageRequestFactory {

    /** Default page size when the client omits {@code size}. */
    public static final int DEFAULT_SIZE = 20;

    /** Hard upper bound on page size, regardless of what the client requests. */
    public static final int MAX_SIZE = 100;

    /**
     * Builds a bounded {@link Pageable}.
     *
     * @param page zero-based page index; negative/absent values clamp to {@code 0}.
     * @param size requested page size; clamped to {@code [1, MAX_SIZE]}, defaulting to {@code 20}.
     * @param sort sort expression as {@code "field,asc|desc"} (direction optional, defaults asc);
     *             blank/absent yields unsorted.
     * @return a safe {@link Pageable}.
     */
    public Pageable of(Integer page, Integer size, String sort) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Sort parsedSort = parseSort(sort);
        return PageRequest.of(safePage, safeSize, parsedSort);
    }

    /**
     * Parses {@code "field,asc|desc"} into a {@link Sort}. Returns {@link Sort#unsorted()} for blank
     * input. Only a single sort clause is supported at the kernel level; resources needing multi-sort
     * compose their own (KISS).
     */
    private Sort parseSort(String sort) {
        if (!StringUtils.hasText(sort)) {
            return Sort.unsorted();
        }
        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (property.isEmpty()) {
            return Sort.unsorted();
        }
        Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return Sort.by(direction, property);
    }
}
