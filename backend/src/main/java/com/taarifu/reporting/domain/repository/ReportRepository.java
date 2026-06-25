package com.taarifu.reporting.domain.repository;

import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportStatus;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    /**
     * Lists <b>all</b> of a subject's reports for the PDPA fan-out (data-subject ACCESS export + ERASURE
     * severing; ADR-0016 §4/§5). Unlike the paged citizen-tracking {@link #findByReporterProfileId} this is the
     * unbounded set the erasure handler must sever and the export contributor must enumerate — a subject's
     * report footprint is bounded in practice and is read once per DSR.
     *
     * @param reporterProfileId the reporter's account public id (the DSR subject).
     * @return every (non-deleted) report still linked to this reporter; empty if none / already anonymised.
     */
    List<Report> findAllByReporterProfileId(UUID reporterProfileId);

    // ------------------------------ Admin console read surface (M14) ------------------------------

    /**
     * The owner-grade admin report queue with all filter dimensions optional and SLA-breach computed
     * in-query (M14; PRD §10 US-3.4, §24.3). A {@code null} parameter disables that dimension; the
     * {@code slaBreached} tri-state is driven by {@code slaBreachedFilter} ({@code null} = ignore,
     * {@code true} = only breached, {@code false} = only non-breached), where "breached" means the case is
     * still active ({@code status not in :terminalStatuses}) and its {@code dueAt} is before {@code now}.
     *
     * <p>The category is {@code join fetch}ed to avoid the N+1 on {@code categoryName} when mapping rows.
     * Newest-filed first. WHY a single nullable-param JPQL (not a {@code Specification}): four optional
     * filters is the simplest case the static query handles cleanly (KISS); the query is the second gate
     * and stays greppable.</p>
     *
     * @param status            optional status filter (enum), or {@code null} for any.
     * @param categoryId        optional issue-category {@code publicId}, or {@code null} for any.
     * @param wardId            optional ward {@code publicId}, or {@code null} for any.
     * @param slaBreachedFilter {@code null} ignore / {@code true} only breached / {@code false} only not.
     * @param terminalStatuses  the terminal statuses (excluded from "active" for breach purposes).
     * @param now               the instant SLA breach is evaluated against.
     * @param pageable          bounded paging/sorting.
     * @return a page of matching reports (category initialised).
     */
    @Query("""
            select r from Report r join fetch r.category c
            where (:status is null or r.status = :status)
              and (:categoryId is null or c.publicId = :categoryId)
              and (:wardId is null or r.reporterWardId = :wardId)
              and (
                    :slaBreachedFilter is null
                 or (:slaBreachedFilter = true
                        and r.dueAt is not null and r.dueAt < :now
                        and r.status not in :terminalStatuses)
                 or (:slaBreachedFilter = false
                        and not (r.dueAt is not null and r.dueAt < :now
                                 and r.status not in :terminalStatuses))
              )
            """)
    Page<Report> adminSearch(@Param("status") ReportStatus status,
                             @Param("categoryId") UUID categoryId,
                             @Param("wardId") UUID wardId,
                             @Param("slaBreachedFilter") Boolean slaBreachedFilter,
                             @Param("terminalStatuses") Collection<ReportStatus> terminalStatuses,
                             @Param("now") Instant now,
                             Pageable pageable);

    /**
     * Counts (non-deleted) reports grouped by lifecycle status, for the admin dashboard (M14, UC-H06).
     * A status with no reports does not appear in the result.
     *
     * @return one {@link StatusCount} row per occupied status.
     */
    @Query("select r.status as status, count(r) as count from Report r group by r.status")
    List<StatusCount> countByStatus();

    /**
     * Counts reports whose status is NOT in the given terminal set — i.e. open cases (M14, UC-H06).
     *
     * @param terminalStatuses the terminal statuses to exclude.
     * @return the open-case count.
     */
    long countByStatusNotIn(Collection<ReportStatus> terminalStatuses);

    /**
     * Counts SLA-breached open cases — still-active reports with a {@code dueAt} before {@code now}
     * (M14). Mirrors the queue's breach predicate so the dashboard and the queue agree.
     *
     * @param now              the instant breach is evaluated against.
     * @param terminalStatuses the terminal statuses (excluded from "active").
     * @return the breached open-case count.
     */
    @Query("""
            select count(r) from Report r
            where r.dueAt is not null and r.dueAt < :now and r.status not in :terminalStatuses
            """)
    long countSlaBreached(@Param("now") Instant now,
                          @Param("terminalStatuses") Collection<ReportStatus> terminalStatuses);

    /**
     * Spring Data interface projection for the {@link #countByStatus()} grouping — keeps the GROUP BY
     * result out of the cross-module boundary; the service maps it to the published {@code ReportStatusCount}
     * DTO.
     */
    interface StatusCount {

        /** @return the lifecycle status of this group. */
        ReportStatus getStatus();

        /** @return the number of reports in this status. */
        long getCount();
    }
}
