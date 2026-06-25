package com.taarifu.engagement.domain.repository;

import com.taarifu.engagement.domain.model.Survey;
import com.taarifu.engagement.domain.model.enums.SurveyStatus;
import com.taarifu.engagement.domain.model.enums.SurveyType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Survey} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: survey lookups by {@code publicId} and public listing by status (only non-DRAFT
 * surveys are publicly visible — PRD §22.6). Soft-deleted rows are excluded by the entity's
 * {@code @SQLRestriction}.</p>
 */
public interface SurveyRepository extends JpaRepository<Survey, Long> {

    /**
     * @param publicId the survey's public id.
     * @return the matching survey, or empty if none/soft-deleted.
     */
    Optional<Survey> findByPublicId(UUID publicId);

    /**
     * Lists surveys whose status is in the given set (public visibility filter).
     *
     * @param statuses the allowed statuses.
     * @param pageable bounded paging/sorting.
     * @return a page of surveys.
     */
    Page<Survey> findByStatusIn(Collection<SurveyStatus> statuses, Pageable pageable);

    /**
     * Lists publicly-visible surveys/polls filtered to a type (SURVEY / POLL) — the citizen read-depth filter
     * so a constituent can browse, e.g., only binding/non-binding polls. Hits {@code ix_survey_type} +
     * {@code ix_survey_status}. Soft-deleted/DRAFT rows are excluded by the {@code @SQLRestriction} and the
     * {@code statuses} set respectively.
     *
     * @param type     the SURVEY/POLL discriminator to filter by.
     * @param statuses the allowed (public) statuses.
     * @param pageable bounded paging/sorting.
     * @return a page of surveys of that type.
     */
    Page<Survey> findByTypeAndStatusIn(SurveyType type, Collection<SurveyStatus> statuses, Pageable pageable);
}
