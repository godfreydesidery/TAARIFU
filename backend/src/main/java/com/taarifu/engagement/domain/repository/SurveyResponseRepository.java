package com.taarifu.engagement.domain.repository;

import com.taarifu.engagement.domain.model.SurveyResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data repository for {@link SurveyResponse} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: supports the one-response-per-person check (US-8.2). The DB unique constraint is the
 * hard guarantee; this {@code exists} query is the fast pre-check so the service can return a clean
 * {@link com.taarifu.common.error.ErrorCode#CONFLICT} envelope.</p>
 */
public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, Long> {

    /**
     * @param surveyPublicId     the survey's public id.
     * @param responderProfileId the candidate responder's identity {@code Profile} public id.
     * @return {@code true} if this responder has already responded to this survey (one-per-person pre-check).
     */
    boolean existsBySurvey_PublicIdAndResponderProfileId(UUID surveyPublicId, UUID responderProfileId);
}
