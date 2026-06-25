package com.taarifu.moderation.domain.repository;

import com.taarifu.moderation.domain.model.ModerationAction;
import com.taarifu.moderation.domain.repository.projection.CountByKeyProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for the append-only {@link ModerationAction} log (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: INSERT + SELECT only — actions are never updated/deleted (§18, §25.8; the V41 grant
 * enforces this at the database). Supports fetching an action by public id (to file/decide an appeal
 * against it) and listing a queue item's action history.</p>
 */
public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

    /**
     * @param publicId the action's public id.
     * @return the action, or empty.
     */
    Optional<ModerationAction> findByPublicId(UUID publicId);

    /**
     * @param itemId the owning {@link ModerationAction#getItem() item}'s internal id.
     * @return that item's actions, newest decisions appended last.
     */
    List<ModerationAction> findByItemIdOrderByTakenAtAsc(Long itemId);

    // --- Transparency report aggregations (§25, M-Phase 3; ADR-0018) — PII-free counts only -------------

    /**
     * Counts moderation actions taken in {@code [from, to)} grouped by {@link ModerationAction#getType() type}
     * — the action-mix breakdown for the transparency report (§25). Returns code-keyed counts only; no
     * moderator, subject, or content. Reads the <b>append-only</b> action log (V41 immutable), so the figures
     * are tamper-evident.
     *
     * @param from inclusive window start (UTC).
     * @param to   exclusive window end (UTC).
     * @return one {@link CountByKeyProjection} per action type present in the window.
     */
    @Query("""
            SELECT a.type AS key, COUNT(a) AS count
            FROM ModerationAction a
            WHERE a.takenAt >= :from AND a.takenAt < :to
            GROUP BY a.type
            """)
    List<CountByKeyProjection> countByTypeInWindow(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * @param from inclusive window start (UTC).
     * @param to   exclusive window end (UTC).
     * @return total moderation actions taken in the window.
     */
    @Query("SELECT COUNT(a) FROM ModerationAction a WHERE a.takenAt >= :from AND a.takenAt < :to")
    long countInWindow(@Param("from") Instant from, @Param("to") Instant to);
}
