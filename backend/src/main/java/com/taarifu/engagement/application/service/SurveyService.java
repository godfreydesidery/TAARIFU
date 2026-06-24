package com.taarifu.engagement.application.service;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.engagement.api.dto.SurveyDto;
import com.taarifu.engagement.application.mapper.EngagementMapper;
import com.taarifu.engagement.domain.model.Survey;
import com.taarifu.engagement.domain.model.SurveyResponse;
import com.taarifu.engagement.domain.model.enums.SurveyStatus;
import com.taarifu.engagement.domain.model.enums.SurveyType;
import com.taarifu.engagement.domain.repository.SurveyRepository;
import com.taarifu.engagement.domain.repository.SurveyResponseRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Application service for surveys/polls — create, list, view, and respond (PRD §12.2 M8, §23.5 fence).
 *
 * <p>Responsibility: owns the transaction boundary and the eligibility rules for surveys. Responding to a
 * <b>non-binding survey</b> is a T2 action; responding to a <b>binding poll</b> is a democratic-weight
 * act — T3 + one-per-person, never balance-gated. The <i>tier</i> half of that gate is applied at the
 * controller ({@code @RequiresTier} chosen per binding-ness); this service enforces <b>one-per-person</b>
 * (DB unique + pre-check) and the open-window rule. Token balance is never consulted (PRD §23.5).</p>
 *
 * <p>WHY the binding-tier decision is split between controller and service: the {@code @RequiresTier}
 * aspect resolves the live tier (MF-2) and must sit on the method; since binding-ness is per-survey
 * (data, not static), the controller declares the <b>stricter</b> T3 requirement for the respond endpoint
 * and the service additionally rejects a binding response from anyone the fence would exclude. A
 * non-binding survey is reachable at T2; see the controller for the exact gate and the
 * {@code // TODO(wiring)} for the per-survey tier refinement.</p>
 */
@Service
@Transactional
public class SurveyService {

    /** Survey statuses that are publicly visible (everything except DRAFT — PRD §22.6). */
    private static final List<SurveyStatus> PUBLIC_STATUSES = List.of(
            SurveyStatus.SCHEDULED, SurveyStatus.OPEN,
            SurveyStatus.CLOSED, SurveyStatus.ARCHIVED);

    private final SurveyRepository surveys;
    private final SurveyResponseRepository responses;
    private final EngagementMapper mapper;
    private final OutboxWriter outboxWriter;

    /**
     * @param surveys      survey persistence port.
     * @param responses    response persistence port (one-per-person pre-check).
     * @param mapper       entity→DTO mapper.
     * @param outboxWriter the transactional-outbox port; {@link #respond} appends a survey_responded analytics
     *                     fact in the response transaction so the analytics sink records it asynchronously, off
     *                     the responder's path (Appendix E, M15). The token ledger is NEVER consulted here (the
     *                     integrity fence, D18/§23.5).
     */
    public SurveyService(SurveyRepository surveys,
                         SurveyResponseRepository responses,
                         EngagementMapper mapper,
                         OutboxWriter outboxWriter) {
        this.surveys = surveys;
        this.responses = responses;
        this.mapper = mapper;
        this.outboxWriter = outboxWriter;
    }

    /**
     * Lists publicly-visible surveys (non-DRAFT), paged.
     *
     * @param pageable bounded paging/sorting.
     * @return a page of {@link SurveyDto}.
     */
    @Transactional(readOnly = true)
    public Page<SurveyDto> listPublic(Pageable pageable) {
        return surveys.findByStatusIn(PUBLIC_STATUSES, pageable).map(mapper::toSurveyDto);
    }

    /**
     * Fetches a single survey by public id.
     *
     * @param publicId the survey's public id.
     * @return the {@link SurveyDto}.
     * @throws ResourceNotFoundException if not found/soft-deleted.
     */
    @Transactional(readOnly = true)
    public SurveyDto get(UUID publicId) {
        return mapper.toSurveyDto(require(publicId));
    }

    /**
     * Creates a survey/poll in {@code DRAFT} on behalf of the authenticated creator.
     *
     * @param title           title.
     * @param description     optional description.
     * @param typeRaw         {@code SURVEY}/{@code POLL} (validated here).
     * @param binding         whether a poll is binding (ignored for SURVEY by the entity).
     * @param audienceScope   JSON audience descriptor, or {@code null}.
     * @param questions       JSON questions definition, or {@code null}.
     * @param startsAt        open instant, or {@code null}.
     * @param endsAt          close instant, or {@code null}.
     * @param anonymous       whether responses are anonymous.
     * @param creatorPublicId the authenticated creator's account public id.
     * @return the created {@link SurveyDto} (status {@code DRAFT}).
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if {@code typeRaw} is invalid.
     */
    public SurveyDto create(String title, String description, String typeRaw, boolean binding,
                            String audienceScope, String questions, Instant startsAt, Instant endsAt,
                            boolean anonymous, UUID creatorPublicId) {
        SurveyType type = parseType(typeRaw);
        // TODO(wiring): resolve creatorPublicId (account) -> identity Profile public id once wired.
        Survey survey = Survey.create(title, description, type, binding, audienceScope, questions,
                startsAt, endsAt, anonymous, creatorPublicId, null);
        surveys.save(survey);
        return mapper.toSurveyDto(survey);
    }

    /**
     * Records a response to a survey/poll (UC-E07, US-8.2).
     *
     * <p><b>Integrity (D18, PRD §23.5):</b> one response per person (DB unique + this pre-check). For a
     * binding poll the response is a democratic-weight act — the T3 gate is the controller's
     * {@code @RequiresTier}; this method additionally rejects a response to a binding poll if the survey
     * is not open. Token balance is never consulted.</p>
     *
     * @param surveyPublicId    the survey to respond to.
     * @param responderPublicId the authenticated responder's account public id (from {@code CurrentUser}).
     * @param answers           the JSON answers payload.
     * @return the {@link SurveyDto} of the survey responded to.
     * @throws ResourceNotFoundException if the survey does not exist.
     * @throws ApiException {@link ErrorCode#CONFLICT} if the survey is not OPEN, or the responder already
     *                      responded (one-per-person).
     */
    public SurveyDto respond(UUID surveyPublicId, UUID responderPublicId, String answers) {
        Survey survey = require(surveyPublicId);

        if (!survey.isAcceptingResponses()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }

        // One-per-person FAST pre-check (the DB unique constraint is the hard guarantee under race).
        if (responses.existsBySurvey_PublicIdAndResponderProfileId(surveyPublicId, responderPublicId)) {
            throw new ApiException(ErrorCode.CONFLICT);
        }

        // TODO(wiring): audience eligibility (geo/role match against audienceScope) + per-survey binding-tier
        // refinement are wired with the identity/geography scope seam. Token balance is NOT read (fence).

        SurveyResponse response = SurveyResponse.of(survey, responderPublicId, answers);
        try {
            responses.save(response);
        } catch (DataIntegrityViolationException dup) {
            // Concurrent double-response hit the unique index — the fence held; surface a clean conflict.
            throw new ApiException(ErrorCode.CONFLICT, dup);
        }

        // ANALYTICS (Appendix E, M15): emit a survey_responded civic-activity fact on the outbox in THIS
        // transaction; the analytics sink records it ASYNCHRONOUSLY, off the responder's path. activeRole is
        // CITIZEN; the `outcome` carries whether this was a binding poll vs a non-binding survey (controlled
        // vocab). Ids/codes ONLY — NO responder identity, NO answers payload (PRD §18, PDPA, ADR-0014 §1). WHY
        // this does NOT breach the fence (D18/§23.5): analytics is a passive side-record emitted AFTER the act
        // completes; it neither reads the token balance nor affects the one-per-person guarantee above.
        outboxWriter.append(EventEnvelope.of(
                AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED,
                AnalyticsEventTypes.AGGREGATE_CIVIC_ACTIVITY,
                survey.getPublicId(),
                new CivicActivityRecorded(
                        AnalyticsEventTypes.SURVEY_RESPONDED,
                        Instant.now(),
                        null,                                          // actorRef: no pseudonymous hash here
                        null,                                          // geoAreaId: n/a
                        null,                                          // categoryId: n/a
                        null,                                          // tier: not resolved at this layer
                        null,                                          // channel: not resolved at this layer
                        "CITIZEN",                                     // activeRole name (string — NOT the enum)
                        null,                                          // latencySeconds: n/a
                        null,                                          // breachType: n/a
                        survey.isBinding() ? "BINDING" : "SURVEY"),    // outcome = binding-ness (controlled vocab)
                Instant.now()));
        return mapper.toSurveyDto(survey);
    }

    /** Loads a survey by public id or throws a localised not-found. */
    private Survey require(UUID publicId) {
        return surveys.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("engagement.survey.notFound", publicId));
    }

    /** Parses the raw survey type, mapping an unknown value to a clean {@link ErrorCode#BAD_REQUEST}. */
    private SurveyType parseType(String raw) {
        try {
            return SurveyType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
    }
}
