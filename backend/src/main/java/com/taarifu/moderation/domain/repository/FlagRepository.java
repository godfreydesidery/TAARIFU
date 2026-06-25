package com.taarifu.moderation.domain.repository;

import com.taarifu.moderation.domain.model.Flag;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.domain.repository.projection.CountByKeyProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Flag} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: persists citizen flags and supports the de-duplication that keeps a subject's flag
 * count honest — {@link #existsByFlaggerProfileIdAndSubjectTypeAndSubjectId} backs the
 * one-flag-per-citizen-per-subject rule before insert (the DB UNIQUE constraint is the hard backstop),
 * and {@link #findBySubjectTypeAndSubjectId} gathers a subject's flags to close them when its queue item
 * is resolved.</p>
 */
public interface FlagRepository extends JpaRepository<Flag, Long> {

    /**
     * @param publicId the flag's public id.
     * @return the flag, or empty.
     */
    Optional<Flag> findByPublicId(UUID publicId);

    /**
     * @param flaggerProfileId the flagging citizen's profile public id.
     * @param subjectType      the kind of content.
     * @param subjectId        the content's public id.
     * @return {@code true} if this citizen already has a live flag on this subject (anti-brigading).
     */
    boolean existsByFlaggerProfileIdAndSubjectTypeAndSubjectId(
            UUID flaggerProfileId, FlagSubjectType subjectType, UUID subjectId);

    /**
     * @param subjectType the kind of content.
     * @param subjectId   the content's public id.
     * @return all live flags on this subject (used to close them when the queue item is resolved).
     */
    List<Flag> findBySubjectTypeAndSubjectId(FlagSubjectType subjectType, UUID subjectId);

    // --- Transparency report aggregations (§25, M-Phase 3; ADR-0018) — PII-free counts only -------------

    /**
     * Counts flags raised in {@code [from, to)} grouped by {@link Flag#getReason() reason} — the abuse-report
     * breakdown for the transparency report (§25). Returns code-keyed counts only; no flagger, subject, or
     * content.
     *
     * @param from inclusive window start (UTC).
     * @param to   exclusive window end (UTC).
     * @return one {@link CountByKeyProjection} per reason present in the window.
     */
    @Query("""
            SELECT f.reason AS key, COUNT(f) AS count
            FROM Flag f
            WHERE f.createdAt >= :from AND f.createdAt < :to
            GROUP BY f.reason
            """)
    List<CountByKeyProjection> countByReasonInWindow(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * @param from inclusive window start (UTC).
     * @param to   exclusive window end (UTC).
     * @return total flags raised in the window (the abuse-report-rate numerator).
     */
    @Query("SELECT COUNT(f) FROM Flag f WHERE f.createdAt >= :from AND f.createdAt < :to")
    long countInWindow(@Param("from") Instant from, @Param("to") Instant to);
}
