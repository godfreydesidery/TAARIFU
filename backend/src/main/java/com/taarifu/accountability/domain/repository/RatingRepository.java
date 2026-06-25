package com.taarifu.accountability.domain.repository;

import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Rating} (ARCHITECTURE.md §3.3; §23 fence).
 *
 * <p>Responsibility: the one-per-person duplicate check, the rater's own row lookup (for revise/owner
 * checks), and the <b>computed</b> aggregate score. There is deliberately <b>no token-balance read
 * anywhere on this path</b> — the civic-integrity fence forbids it (D18, §23).</p>
 */
public interface RatingRepository extends JpaRepository<Rating, Long> {

    /**
     * @param publicId the rating's public id.
     * @return the rating, or empty.
     */
    Optional<Rating> findByPublicId(UUID publicId);

    /**
     * The one-per-person lookup: does this rater already have a rating for this subject and period?
     * Backs both the pre-insert duplicate guard and the rater's revise path (only their own row).
     *
     * @param subjectType    the subject kind.
     * @param subjectId      the rated subject's public id.
     * @param raterProfileId the rater's profile public id.
     * @param period         the rating period.
     * @return the existing rating, or empty.
     */
    Optional<Rating> findBySubjectTypeAndSubjectIdAndRaterProfileIdAndPeriod(
            RatingSubjectType subjectType, UUID subjectId, UUID raterProfileId, String period);

    /**
     * Lists every rating the subject authored as a rater, for the PDPA fan-out (data-subject ACCESS export +
     * ERASURE de-identification; ADR-0016 §4/§5). The erasure handler replaces each rater reference with a
     * deterministic tombstone (preserving the computed aggregate — §23.5), so a redelivery finds none still
     * on the real rater id.
     *
     * @param raterProfileId the rater's account public id (the DSR subject).
     * @return every (non-deleted) rating still attributed to this rater; empty if none / already severed.
     */
    List<Rating> findByRaterProfileId(UUID raterProfileId);

    /**
     * Computes the aggregate rating for a subject across all raters and periods.
     *
     * <p>WHY computed (never stored on the subject): the aggregate must be a faithful function of the
     * append-only rating rows so it cannot be inflated independently of real one-per-person ratings —
     * the integrity fence in arithmetic form (§23). Returns {@code count=0, average=null} for an unrated
     * subject.</p>
     *
     * @param subjectType the subject kind.
     * @param subjectId   the rated subject's public id.
     * @return a projection of {@code count} and {@code average} score.
     */
    @Query("""
            select count(r) as count, avg(r.score) as average
            from Rating r
            where r.subjectType = :subjectType and r.subjectId = :subjectId
            """)
    RatingAggregate aggregate(@Param("subjectType") RatingSubjectType subjectType,
                              @Param("subjectId") UUID subjectId);

    /**
     * Read projection for the computed aggregate rating.
     */
    interface RatingAggregate {
        /** @return number of ratings counted. */
        long getCount();

        /** @return the average score, or {@code null} when there are no ratings. */
        Double getAverage();
    }
}
