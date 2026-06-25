package com.taarifu.moderation.domain.repository;

import com.taarifu.moderation.api.dto.AppealSummaryDto;
import com.taarifu.moderation.domain.model.Appeal;
import com.taarifu.moderation.domain.model.enums.AppealStatus;
import com.taarifu.moderation.domain.repository.projection.CountByKeyProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Appeal} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: drives the appeal queue (UC-H03) and the one-live-appeal-per-action rule —
 * {@link #existsByActionPublicId} backs the pre-insert check (the DB UNIQUE constraint is the backstop),
 * {@link #findByStatus} renders the open-appeals queue, and {@link #findSummaries}/
 * {@link #findSummariesByStatus} render the paged moderator appeals queue summary
 * ({@code GET /moderation/appeals}) for an <i>independent</i> moderator.</p>
 */
public interface AppealRepository extends JpaRepository<Appeal, Long> {

    /**
     * @param publicId the appeal's public id.
     * @return the appeal, or empty.
     */
    Optional<Appeal> findByPublicId(UUID publicId);

    /**
     * @param actionPublicId the appealed action's public id.
     * @return {@code true} if a live appeal already exists for this action.
     */
    boolean existsByActionPublicId(UUID actionPublicId);

    /**
     * @param status   the appeal status to list (typically {@code OPEN}).
     * @param pageable page + sort.
     * @return the paged appeal queue.
     */
    Page<Appeal> findByStatus(AppealStatus status, Pageable pageable);

    /**
     * Renders the moderator <b>appeals queue</b> summary across every status (UC-H03;
     * {@code GET /moderation/appeals} with no {@code status} filter).
     *
     * <p>WHY a single constructor-expression projection joining {@code appeal → action → item}: the queue
     * row needs the actioned content kind ({@code item.subjectType}), which lives on the queue item the
     * appealed action resolved. Selecting it in one query avoids the N+1 that a {@code Page<Appeal>} mapped
     * in Java would trigger (each row lazy-loading {@code action.item}), and it returns only the lean
     * summary fields — no grounds, no decision note, no moderated content (data minimisation, §18). Sort +
     * paging are applied by the caller's {@link Pageable} (default {@code createdAt,desc} → newest first).</p>
     *
     * @param pageable page + sort (e.g. {@code createdAt,desc}).
     * @return the paged appeal summary rows.
     */
    @Query("""
            SELECT new com.taarifu.moderation.api.dto.AppealSummaryDto(
                a.publicId, a.action.item.subjectType, a.appellantProfileId, a.status, a.createdAt)
            FROM Appeal a
            """)
    Page<AppealSummaryDto> findSummaries(Pageable pageable);

    /**
     * Renders the moderator <b>appeals queue</b> summary filtered to one status (UC-H03;
     * {@code GET /moderation/appeals?status=OPEN}).
     *
     * <p>Same projection contract as {@link #findSummaries}, restricted to the requested
     * {@link AppealStatus}. The {@code status} predicate plus {@code createdAt} ordering is backed by the
     * V100 composite index {@code ix_mod_appeal_status_created}.</p>
     *
     * @param status   the appeal status to list (e.g. {@code OPEN}).
     * @param pageable page + sort.
     * @return the paged appeal summary rows for that status.
     */
    @Query("""
            SELECT new com.taarifu.moderation.api.dto.AppealSummaryDto(
                a.publicId, a.action.item.subjectType, a.appellantProfileId, a.status, a.createdAt)
            FROM Appeal a
            WHERE a.status = :status
            """)
    Page<AppealSummaryDto> findSummariesByStatus(@Param("status") AppealStatus status, Pageable pageable);

    // --- Transparency report aggregations (§25, M-Phase 3; ADR-0018) — PII-free counts only -------------

    /**
     * Counts appeals filed in {@code [from, to)} grouped by {@link Appeal#getStatus() status} — the
     * appeal-outcome (fairness) breakdown for the transparency report (§25). Windowed on {@code createdAt}
     * (when the appeal was filed) so an OPEN appeal still in flight is counted. Returns code-keyed counts
     * only; no appellant, moderator, or content.
     *
     * @param from inclusive window start (UTC).
     * @param to   exclusive window end (UTC).
     * @return one {@link CountByKeyProjection} per appeal status present in the window.
     */
    @Query("""
            SELECT a.status AS key, COUNT(a) AS count
            FROM Appeal a
            WHERE a.createdAt >= :from AND a.createdAt < :to
            GROUP BY a.status
            """)
    List<CountByKeyProjection> countByStatusInWindow(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * @param from inclusive window start (UTC).
     * @param to   exclusive window end (UTC).
     * @return total appeals filed in the window.
     */
    @Query("SELECT COUNT(a) FROM Appeal a WHERE a.createdAt >= :from AND a.createdAt < :to")
    long countInWindow(@Param("from") Instant from, @Param("to") Instant to);
}
