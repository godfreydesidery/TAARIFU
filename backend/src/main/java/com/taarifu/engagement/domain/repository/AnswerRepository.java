package com.taarifu.engagement.domain.repository;

import com.taarifu.engagement.domain.model.Answer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Answer} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: fetch the single answer for a question (one answer per question — see the entity's
 * unique constraint) for the question-detail read. Soft-deleted rows are excluded by the entity's
 * {@code @SQLRestriction}.</p>
 */
public interface AnswerRepository extends JpaRepository<Answer, Long> {

    /**
     * @param questionPublicId the answered question's public id.
     * @return the answer for that question, or empty if unanswered.
     */
    Optional<Answer> findByQuestion_PublicId(UUID questionPublicId);
}
