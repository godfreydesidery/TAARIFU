package com.taarifu.reporting.api.dto;

import java.util.List;

/**
 * A transport-neutral page of {@link AdminReportSummary} rows returned by
 * {@link com.taarifu.reporting.api.ReportQueryApi#adminQuery} to the admin console (M14; ADR-0013 §1).
 *
 * <p>Responsibility: carry a slice of queue rows plus the totals the admin module needs to build the wire
 * {@code meta} block, <b>without</b> leaking Spring Data's {@code Page} type across the module boundary
 * (ADR-0013 §1 — only DTOs/enums/UUIDs cross). Mirrors the established
 * {@code com.taarifu.admin.api.spi.AdminPage} shape so the admin module maps any port page to a
 * {@code PageMeta} identically (DRY). The reporting side paginates over its own tables and maps to this
 * record.</p>
 *
 * @param content       the rows on this page (never {@code null}; empty on an out-of-range page).
 * @param page          the zero-based page index that was returned.
 * @param size          the page size that was requested.
 * @param totalElements the total number of matching rows across all pages.
 */
public record AdminReportPage(
        List<AdminReportSummary> content,
        int page,
        int size,
        long totalElements
) {

    /**
     * @return the total number of pages for the current {@link #size()} (ceil of
     *         {@code totalElements / size}); {@code 0} when {@code size <= 0}.
     */
    public int totalPages() {
        if (size <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) totalElements / (double) size);
    }
}
