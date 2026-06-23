package com.taarifu.engagement.domain.repository;

import com.taarifu.engagement.domain.model.Question;
import com.taarifu.engagement.domain.model.enums.QuestionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Question} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: question lookups by {@code publicId}, public listing by status (only OPEN/ANSWERED
 * are publicly visible), and listing the questions put to a given representative (the rep's Q&A inbox —
 * the rep's public id is an institutions reference used here only as a filter value). Soft-deleted rows
 * are excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface QuestionRepository extends JpaRepository<Question, Long> {

    /**
     * @param publicId the question's public id.
     * @return the matching question, or empty if none/soft-deleted.
     */
    Optional<Question> findByPublicId(UUID publicId);

    /**
     * Lists questions whose status is in the given set (public visibility filter).
     *
     * @param statuses the allowed statuses (e.g. OPEN, ANSWERED).
     * @param pageable bounded paging/sorting.
     * @return a page of questions.
     */
    Page<Question> findByStatusIn(Collection<QuestionStatus> statuses, Pageable pageable);

    /**
     * Lists the publicly-visible questions targeting a given representative.
     *
     * @param targetRepId the representative's public id (institutions reference, filter value only).
     * @param statuses    the allowed statuses (public visibility filter).
     * @param pageable    bounded paging/sorting.
     * @return a page of questions to that representative.
     */
    Page<Question> findByTargetRepIdAndStatusIn(UUID targetRepId, Collection<QuestionStatus> statuses,
                                                Pageable pageable);
}
