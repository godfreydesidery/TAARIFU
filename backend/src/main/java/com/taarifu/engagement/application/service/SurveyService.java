package com.taarifu.engagement.application.service;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.engagement.api.dto.SurveyDto;
import com.taarifu.engagement.application.mapper.EngagementMapper;
import com.taarifu.engagement.domain.model.Survey;
import com.taarifu.engagement.domain.model.SurveyResponse;
import com.taarifu.engagement.domain.model.enums.SurveyStatus;
import com.taarifu.engagement.domain.model.enums.SurveyType;
import com.taarifu.engagement.domain.repository.SurveyRepository;
import com.taarifu.engagement.domain.repository.SurveyResponseRepository;
import com.taarifu.identity.api.ProfileLookupApi;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
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
 * (data, not static), the controller declares the <b>stricter</b> T3 requirement for the respond endpoint,
 * which satisfies the binding-poll fence by construction (every response is reached only above T3; token
 * balance is never read). A non-binding survey could in principle be relaxed to T2 — see the controller's
 * {@code PHASE-3} note (it needs a survey-pre-reading tier aspect that does not yet exist).</p>
 */
@Service
@Transactional
public class SurveyService {

    /** Survey statuses that are publicly visible (everything except DRAFT — PRD §22.6). */
    private static final List<SurveyStatus> PUBLIC_STATUSES = List.of(
            SurveyStatus.SCHEDULED, SurveyStatus.OPEN,
            SurveyStatus.CLOSED, SurveyStatus.ARCHIVED);

    /**
     * Platform-wide staff role names (un-prefixed, as carried on {@code CurrentUser}) that may open any
     * survey/poll regardless of authorship — the staff half of the author-or-staff open gate
     * (SecurityConfig role hierarchy {@code ROOT > ADMIN > MODERATOR}; the names match the JWT role claims).
     */
    private static final List<String> STAFF_ROLES = List.of("MODERATOR", "ADMIN", "ROOT");

    /**
     * Max characters of the survey description carried in the public discovery snippet — a lean public
     * preview only (PRD §15 data budget; well under ADR-0017's {@code snippet_*} 1024-char column).
     */
    private static final int SEARCH_SNIPPET_MAX = 480;

    private final SurveyRepository surveys;
    private final SurveyResponseRepository responses;
    private final EngagementMapper mapper;
    private final OutboxWriter outboxWriter;
    private final SearchIndexApi searchIndex;
    private final ProfileLookupApi profileLookup;

    /**
     * @param surveys      survey persistence port.
     * @param responses    response persistence port (one-per-person pre-check).
     * @param mapper       entity→DTO mapper.
     * @param outboxWriter the transactional-outbox port; {@link #respond} appends a survey_responded analytics
     *                     fact in the response transaction so the analytics sink records it asynchronously, off
     *                     the responder's path (Appendix E, M15). The token ledger is NEVER consulted here (the
     *                     integrity fence, D18/§23.5).
     * @param searchIndex  the search module's published inbound port (ADR-0017 §1, ADR-0013 §1). This service
     *                     <b>pushes</b> a public, PII-free projection of a publicly-visible (non-DRAFT) survey
     *                     or poll (indexed under {@code SearchEntityType.POLL} for both {@code SurveyType}s)
     *                     into the discovery index on create/open/lifecycle-change and <b>removes</b> it when
     *                     not (or no longer) public-safe — owner→search, an {@code api → api} call, never a
     *                     reach-in. The questions JSON and response payloads are NEVER indexed (PRD §18).
     * @param profileLookup identity's published author-resolution port (ADR-0013 §1). {@link #create} maps the
     *                     authenticated <b>account</b> public id to the authoring identity {@code Profile} public
     *                     id through this {@code api → api} call, so the survey/poll is attributed by
     *                     <b>profile</b> id (never the raw account id) — engagement never imports identity's
     *                     {@code domain}. Returns id + public display name only, no PII (PRD §18, PDPA).
     */
    public SurveyService(SurveyRepository surveys,
                         SurveyResponseRepository responses,
                         EngagementMapper mapper,
                         OutboxWriter outboxWriter,
                         SearchIndexApi searchIndex,
                         ProfileLookupApi profileLookup) {
        this.surveys = surveys;
        this.responses = responses;
        this.mapper = mapper;
        this.outboxWriter = outboxWriter;
        this.searchIndex = searchIndex;
        this.profileLookup = profileLookup;
    }

    /**
     * Lists publicly-visible surveys (non-DRAFT), paged.
     *
     * @param pageable bounded paging/sorting.
     * @return a page of {@link SurveyDto}.
     */
    @Transactional(readOnly = true)
    public Page<SurveyDto> listPublic(SurveyType type, Pageable pageable) {
        Page<Survey> page = (type == null)
                ? surveys.findByStatusIn(PUBLIC_STATUSES, pageable)
                : surveys.findByTypeAndStatusIn(type, PUBLIC_STATUSES, pageable);
        return page.map(mapper::toSurveyDto);
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
        // Resolve the authenticated ACCOUNT public id (the JWT-subject grain from CurrentUser) to the authoring
        // identity PROFILE public id via identity's published ProfileLookupApi (api -> api; engagement never
        // imports identity's domain - ADR-0013 §1). The survey/poll is attributed by PROFILE id, never the raw
        // account id; the author-or-staff open gate (#open) compares this stored profile id to the resolved
        // caller profile id. A caller whose account has no resolvable profile (deny-by-default empty) cannot
        // author - clean BAD_REQUEST.
        UUID creatorProfileId = profileLookup.profileIdForAccount(creatorPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST));
        Survey survey = Survey.create(title, description, type, binding, audienceScope, questions,
                startsAt, endsAt, anonymous, creatorProfileId, null);
        surveys.save(survey);
        // SEARCH (ADR-0017 §1, ADR-0013 §1): keep the discovery projection in step with the survey's public
        // visibility. A freshly-created survey is DRAFT, so reindexForDiscovery REMOVES (idempotent no-op on a
        // never-indexed row) — a draft is NEVER discoverable (the no-leak fence, PRD §18). It is upserted once
        // the survey opens (the open path), which routes through this same single helper. The questions JSON and
        // any response payload are NEVER indexed (only the title + description snippet).
        reindexForDiscovery(survey);
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

        // BINDING-TIER FENCE (D18, PRD §23.5): a binding poll is a democratic-weight act gated at T3. That tier
        // gate is enforced by construction at the controller's @RequiresTier("T3") on this endpoint (the
        // strictest requirement, re-resolved LIVE by RequiresTierAspect - MF-2), so the service is reached only
        // above T3 for every response. Token balance is NEVER read here (the fence). The one-per-person guarantee
        // is the DB unique constraint + the pre-check above.
        //
        // PHASE-3: audience eligibility (geo/role match of the responder against the survey's audienceScope JSON)
        // needs an audience-scope evaluator that does not yet exist - identity publishes ElectoralScopeApi
        // (constituency/ward elector checks) but no general "does this responder match this audience descriptor?"
        // port, and there is no shared audienceScope schema/parser. When that evaluator ships (identity/geography
        // audience-scope port, ready to receive this audienceScope), enforce it here before the response is saved
        // (today audienceScope is advisory metadata on the survey, surfaced to clients, not a server-side gate).

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

    /**
     * Opens a survey/poll for responses (UC-E06) and makes it discoverable. Opening is the
     * {@code DRAFT/SCHEDULED -> OPEN} visibility change, so it is when the survey first becomes public-safe and
     * must enter discovery; routing it through the single {@link #reindexForDiscovery(Survey)} fence keeps the
     * index-vs-no-index decision in one place (DRY).
     *
     * <p><b>Authorization (author-or-staff).</b> A survey/poll is opened by its <b>author</b> (the
     * Authority/Representative/Org that drafted it — US-8.1) or by platform <b>staff</b>
     * (MODERATOR/ADMIN/ROOT). The controller's {@code @PreAuthorize} only proves the caller is authenticated;
     * the author-or-staff decision is data-dependent (it compares the survey's {@code creatorProfileId} to the
     * caller), so it is enforced here — deny-by-default: a caller who is neither is {@link ErrorCode#FORBIDDEN}.
     * The caller is taken from {@code CurrentUser}, never the body.</p>
     *
     * @param surveyPublicId the survey to open.
     * @param callerPublicId the authenticated caller's account public id (from {@code CurrentUser}).
     * @return the now-OPEN {@link SurveyDto}.
     * @throws ResourceNotFoundException if the survey does not exist.
     * @throws ApiException {@link ErrorCode#FORBIDDEN} if the caller is neither the author nor staff;
     *                      {@link ErrorCode#CONFLICT} if the survey is not {@code DRAFT}/{@code SCHEDULED}.
     */
    public SurveyDto open(UUID surveyPublicId, UUID callerPublicId) {
        Survey survey = require(surveyPublicId);

        // Author-or-staff gate (deny-by-default). The survey's authorship is stored as a PROFILE id (resolved at
        // create via ProfileLookupApi), so the caller's ACCOUNT id is mapped to its PROFILE id through the same
        // identity port before comparison - apples to apples (api -> api, no import). Staff = any platform staff
        // role on the live principal (the established CurrentUser role-read pattern). An account with no
        // resolvable profile is simply not the author (Optional.empty -> no match) and falls through to staff.
        UUID callerProfileId = callerPublicId == null
                ? null
                : profileLookup.profileIdForAccount(callerPublicId).orElse(null);
        boolean isAuthor = callerProfileId != null && callerProfileId.equals(survey.getCreatorProfileId());
        if (!isAuthor && !callerIsStaff()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }

        try {
            survey.open();
        } catch (IllegalStateException notOpenable) {
            // Already OPEN/CLOSED/ARCHIVED: re-opening is a clean conflict (never resurrect a finished poll).
            throw new ApiException(ErrorCode.CONFLICT, notOpenable);
        }
        // Now publicly visible (OPEN) → upsert the public projection into discovery (ADR-0017 §1).
        reindexForDiscovery(survey);
        return mapper.toSurveyDto(survey);
    }

    /**
     * @return whether the live authenticated principal holds a platform staff role
     *         (MODERATOR/ADMIN/ROOT) — the staff half of the author-or-staff open gate.
     */
    private boolean callerIsStaff() {
        List<String> roles = CurrentUser.current().map(CurrentUser::roles).orElse(List.of());
        return roles.stream().anyMatch(STAFF_ROLES::contains);
    }

    /** Loads a survey by public id or throws a localised not-found. */
    private Survey require(UUID publicId) {
        return surveys.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("engagement.survey.notFound", publicId));
    }

    /**
     * Pushes (or removes) this survey/poll's <b>public, PII-free</b> discovery projection in the search index
     * (ADR-0017 §1; ADR-0013 §1 owner→search). The single place the index-vs-no-index decision lives, so the
     * privacy fence is enforced once across the create/open call sites — mirroring reporting's pattern.
     *
     * <p><b>The fence (PRD §18, ADR-0017 §1/§4):</b> a survey is indexed <b>only if</b> it is publicly visible
     * — anything past {@code DRAFT} ({@link Survey#isPubliclyVisible()}). A {@code DRAFT} is <b>never</b>
     * indexed; any non-public state is positively {@link SearchIndexApi#remove removed} (idempotent on an
     * absent row).</p>
     *
     * <p><b>What is pushed (public-display + opaque ids only — never PII):</b> the survey
     * {@link Survey#getTitle() title} as the discovery label and a {@link #snippet(String) snippet} of the
     * description as the preview. <b>Never the questions JSON, never any response payload</b> (only the public
     * title + description), and never the responder list (responses are private/anonymous — PRD §18).
     * Indexed under {@link SearchEntityType#POLL} for both {@code SURVEY} and {@code POLL}
     * {@link SurveyType}s — engagement models them as one {@code Survey} aggregate, so one search type serves
     * both. {@code authoredByAccountId} carries the creator profile id solely for the search module's
     * suspended-author visibility maintenance (ADR-0017 §3) — never returned.</p>
     *
     * @param survey the survey/poll whose discovery projection is being maintained.
     * @return {@code true} if the survey/poll was upserted into discovery (it is publicly visible — non-DRAFT);
     *         {@code false} if it was removed/absent (DRAFT or any non-public state). The one-off backfill
     *         adapter ({@link com.taarifu.engagement.application.service.search.SurveyBackfillSource}) reuses this
     *         exact method and counts a {@code true} as one indexed row — so the fence can never drift between
     *         the live write path and the backfill (DRY; the
     *         {@link com.taarifu.search.domain.port.SearchBackfillSource} contract). Public (the same-module
     *         backfill adapter lives in a {@code service.search} sub-package and reuses this method directly).
     */
    public boolean reindexForDiscovery(Survey survey) {
        if (!survey.isPubliclyVisible()) {
            // DRAFT (or any non-public state): ensure it is absent from discovery (idempotent remove).
            searchIndex.remove(SearchEntityType.POLL, survey.getPublicId());
            return false;
        }
        searchIndex.upsert(new SearchDocumentUpsert(
                SearchEntityType.POLL,
                survey.getPublicId(),
                survey.getTitle(),
                // The public description is the snippet for both locales (Swahili-first; the FTS config is
                // `simple` — no per-language stemming — so one snippet serves SW/EN inputs). NEVER the
                // questions JSON or any response data.
                snippet(survey.getDescription()),
                snippet(survey.getDescription()),
                // Keywords: the type + binding-ness as searchable terms — public, non-PII.
                survey.getType().name() + (survey.isBinding() ? " BINDING" : ""),
                null,                                  // areaId: audience scope is a JSON descriptor, not a facet
                null,                                  // categoryId: n/a for a survey
                SearchVisibility.PUBLIC,
                // authoredByAccountId: visibility-maintenance only (ADR-0017 §3); never returned. Null for an
                // org-authored survey (an authorless row), which is fine.
                survey.getCreatorProfileId()));
        return true;
    }

    /**
     * Truncates free text to a lean, index-safe discovery snippet ({@link #SEARCH_SNIPPET_MAX} chars),
     * appending an ellipsis when cut. Keeps the index payload lean (PRD §15) and under the index column bound.
     *
     * @param text the source text (may be {@code null}).
     * @return the trimmed snippet, or {@code null} if the input is {@code null}/blank.
     */
    private String snippet(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.length() <= SEARCH_SNIPPET_MAX) {
            return trimmed;
        }
        return trimmed.substring(0, SEARCH_SNIPPET_MAX) + "…";
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
