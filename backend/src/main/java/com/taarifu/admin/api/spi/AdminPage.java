package com.taarifu.admin.api.spi;

import java.util.List;

/**
 * A transport-neutral page of results returned by the {@link IdentityAdminPort} to the admin console
 * (M14; ADR-0013 §1).
 *
 * <p>Responsibility: carry a slice of rows plus the totals the admin module needs to build the wire
 * {@code meta} block, <b>without</b> leaking Spring Data's {@code Page} type across the module boundary
 * (ADR-0013 §1 — only DTOs/enums/UUIDs cross). The identity-side implementation paginates over its own
 * tables and maps to this record; the admin module turns it into a {@code PageMeta} for the envelope.</p>
 *
 * @param content       the rows on this page (never {@code null}; empty on an out-of-range page).
 * @param page          the zero-based page index that was returned.
 * @param size          the page size that was requested.
 * @param totalElements the total number of matching rows across all pages.
 * @param <T>           the row type (e.g. {@link AdminUserView}).
 */
public record AdminPage<T>(List<T> content, int page, int size, long totalElements) {

    /**
     * @return the total number of pages for the current {@link #size()} (a derived convenience; ceil of
     *         {@code totalElements / size}); {@code 0} when {@code size <= 0}.
     */
    public int totalPages() {
        if (size <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) totalElements / (double) size);
    }
}
