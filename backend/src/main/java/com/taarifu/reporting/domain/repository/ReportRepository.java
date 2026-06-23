package com.taarifu.reporting.domain.repository;

import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Report} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: report lookups by {@code publicId}/{@code code} (own-report tracking), the
 * reporter's own list, and the <b>public</b> near-me list/map (US-3.7). Soft-deleted rows are excluded by
 * the entity's {@code @SQLRestriction}.</p>
 *
 * <p>WHY the public queries filter {@code visibility = PUBLIC} in the query (not only in the service):
 * defence-in-depth — a PRIVATE/sensitive report must never leak into a public list even if a caller
 * forgets the filter. The query is the second gate behind the service's visibility check (PRD §25.3,
 * Appendix D.4).</p>
 */
public interface ReportRepository extends JpaRepository<Report, Long> {

    /**
     * @param publicId the report's public id.
     * @return the matching report, or empty if none/soft-deleted.
     */
    Optional<Report> findByPublicId(UUID publicId);

    /**
     * Looks a report up by its human ticket code — the tracking handle for anonymous sensitive filings
     * (D-Q1, §25.3) and the code a citizen quotes.
     *
     * @param code the {@code TAR-YYYY-NNNNNN} ticket code.
     * @return the matching report, or empty.
     */
    Optional<Report> findByCode(String code);

    /**
     * Lists a citizen's own reports (US-3.2 tracking).
     *
     * @param reporterProfileId the reporter profile {@code publicId}.
     * @param pageable          bounded paging/sorting.
     * @return a page of the reporter's reports.
     */
    Page<Report> findByReporterProfileId(UUID reporterProfileId, Pageable pageable);

    /**
     * Lists PUBLIC reports for a ward — the public near-me list/map (US-3.7). Filters visibility in-query
     * as defence-in-depth so a PRIVATE report can never appear here.
     *
     * @param wardId     the ward {@code publicId}.
     * @param visibility must be {@link ReportVisibility#PUBLIC} (the service always passes PUBLIC).
     * @param pageable   bounded paging/sorting.
     * @return a page of public reports in the ward.
     */
    Page<Report> findByReporterWardIdAndVisibility(UUID wardId, ReportVisibility visibility, Pageable pageable);

    /**
     * Lists PUBLIC reports across all wards (the global public list when no ward filter is given).
     *
     * @param visibility must be {@link ReportVisibility#PUBLIC}.
     * @param pageable   bounded paging/sorting.
     * @return a page of public reports.
     */
    Page<Report> findByVisibility(ReportVisibility visibility, Pageable pageable);

    /**
     * Fetches a report by public id together with its category, avoiding the N+1 on category access when
     * mapping a single report.
     *
     * @param publicId the report's public id.
     * @return the report with its category initialised, or empty.
     */
    @Query("select r from Report r join fetch r.category where r.publicId = :publicId")
    Optional<Report> findByPublicIdWithCategory(@Param("publicId") UUID publicId);
}
