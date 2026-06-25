package com.taarifu.engagement.application.service;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.engagement.api.dto.PetitionDto;
import com.taarifu.engagement.application.mapper.EngagementMapper;
import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.PetitionSignature;
import com.taarifu.engagement.domain.model.enums.PetitionStatus;
import com.taarifu.engagement.domain.model.enums.PetitionTargetType;
import com.taarifu.engagement.domain.repository.PetitionRepository;
import com.taarifu.engagement.domain.repository.PetitionSignatureRepository;
import com.taarifu.identity.api.ElectoralScopeApi;
import com.taarifu.institutions.api.RepresentativeQueryApi;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for petitions — create, list, view, and the <b>binding sign action</b>
 * (PRD §12.2 M9, §23.5 integrity fence, D13/D16).
 *
 * <p>Responsibility: owns the transaction boundary and the integrity rules for petitions. The
 * load-bearing method is {@link #sign}: it enforces the <b>civic-integrity fence</b> — the authorisation
 * path checks <b>tier (T3, via the endpoint's {@code @RequiresTier}) + no-self-petition (D16) +
 * electoral scope (D13) + one-per-person (DB unique)</b> and <b>never reads a token balance</b> (PRD §23.5).
 * Electoral-scope enforcement (the signer's single {@code isElectoral} location must match a
 * representative-targeted petition's constituency) resolves the target rep's constituency via institutions'
 * {@link RepresentativeQueryApi} and checks the signer via identity's {@link ElectoralScopeApi} — both
 * published api-package query ports, so engagement imports neither module's internals (ADR-0013).</p>
 *
 * <p>WHY actor references are the authenticated principal's {@code publicId} (the JWT subject): the signer
 * is taken from {@code CurrentUser}, never from the request body, so a caller can only sign <i>as
 * themselves</i> — the foundation of the no-self-petition and one-per-person guards. Mapping the account
 * {@code publicId} to its identity {@code Profile} public id is a later cross-module wiring step (this
 * scaffold references the actor by the account public id consistently for signer + conflict checks).</p>
 */
@Service
@Transactional
public class PetitionService {

    /** Petition statuses that are publicly visible (everything except DRAFT — PRD §22.6). */
    private static final List<PetitionStatus> PUBLIC_STATUSES = List.of(
            PetitionStatus.ACTIVE, PetitionStatus.SUCCEEDED,
            PetitionStatus.RESPONDED, PetitionStatus.CLOSED);

    private final PetitionRepository petitions;
    private final PetitionSignatureRepository signatures;
    private final EngagementMapper mapper;
    private final ScopeGuard scopeGuard;
    private final RepresentativeQueryApi representativeQueryApi;
    private final ElectoralScopeApi electoralScopeApi;
    private final AuditEventService audit;
    private final OutboxWriter outboxWriter;
    private final SearchIndexApi searchIndex;

    /**
     * Max characters of the petition body carried in the public discovery snippet — a lean public preview
     * only (PRD §15 data budget; well under ADR-0017's {@code snippet_*} 1024-char column). The full body is
     * re-read from this module by id when a result is tapped (the index never returns the aggregate — ADR-0013).
     */
    private static final int SEARCH_SNIPPET_MAX = 480;

    /**
     * @param petitions              petition persistence port.
     * @param signatures             signature persistence port (one-per-person pre-check).
     * @param mapper                 entity→DTO mapper.
     * @param scopeGuard             the shared conflict-of-interest seam ({@code @taarifuAuthz}); supplies the
     *                               no-self-action check (D16).
     * @param representativeQueryApi institutions' published port resolving a target rep's constituency (D13).
     * @param electoralScopeApi      identity's published port checking the signer's electoral scope (D13).
     * @param audit                  append-only audit writer (binding-action + denial evidence, L-1).
     * @param outboxWriter           the transactional-outbox port; {@link #sign} appends a petition_signed
     *                               analytics fact in the sign transaction so the analytics sink records it
     *                               asynchronously, off the signer's path (Appendix E, M15). The token ledger is
     *                               NEVER consulted on this path (the integrity fence, D18/§23.5).
     * @param searchIndex            the search module's published inbound port (ADR-0017 §1, ADR-0013 §1). This
     *                               service <b>pushes</b> a public, PII-free projection of a publicly-visible
     *                               (non-DRAFT) petition into the discovery index on create/sign/lifecycle-change
     *                               and <b>removes</b> it when the petition is not (or no longer) public-safe —
     *                               owner→search direction, an {@code api → api} call, never a reach-in. Token
     *                               balance is NEVER read on this path; indexing is a passive side-record, not a
     *                               gate (the fence stays intact, D18/§23.5).
     */
    public PetitionService(PetitionRepository petitions,
                           PetitionSignatureRepository signatures,
                           EngagementMapper mapper,
                           ScopeGuard scopeGuard,
                           RepresentativeQueryApi representativeQueryApi,
                           ElectoralScopeApi electoralScopeApi,
                           AuditEventService audit,
                           OutboxWriter outboxWriter,
                           SearchIndexApi searchIndex) {
        this.petitions = petitions;
        this.signatures = signatures;
        this.mapper = mapper;
        this.scopeGuard = scopeGuard;
        this.representativeQueryApi = representativeQueryApi;
        this.electoralScopeApi = electoralScopeApi;
        this.audit = audit;
        this.outboxWriter = outboxWriter;
        this.searchIndex = searchIndex;
    }

    /**
     * Lists publicly-visible petitions (non-DRAFT), paged.
     *
     * @param pageable bounded paging/sorting.
     * @return a page of {@link PetitionDto}.
     */
    @Transactional(readOnly = true)
    public Page<PetitionDto> listPublic(Pageable pageable) {
        return petitions.findByStatusIn(PUBLIC_STATUSES, pageable).map(mapper::toPetitionDto);
    }

    /**
     * Fetches a single petition by public id.
     *
     * @param publicId the petition's public id.
     * @return the {@link PetitionDto}.
     * @throws ResourceNotFoundException if not found/soft-deleted.
     */
    @Transactional(readOnly = true)
    public PetitionDto get(UUID publicId) {
        return mapper.toPetitionDto(require(publicId));
    }

    /**
     * Creates a petition in {@code DRAFT} on behalf of the authenticated creator.
     *
     * @param title         headline.
     * @param body          the ask.
     * @param targetTypeRaw  {@code REPRESENTATIVE}/{@code OFFICE} (validated here).
     * @param targetId      the addressee's public id (institutions module; by id only).
     * @param signatureGoal success threshold (validated ≥ 1 at the edge).
     * @param deadline      optional deadline.
     * @param creatorPublicId the authenticated creator's account public id (taken from {@code CurrentUser}).
     * @return the created {@link PetitionDto} (status {@code DRAFT}).
     * @throws ApiException {@link ErrorCode#CONFLICT_OF_INTEREST} if the creator targets themselves (a rep
     *                      may not petition against their own representative record — D16);
     *                      {@link ErrorCode#BAD_REQUEST} if {@code targetTypeRaw} is not a valid type.
     */
    public PetitionDto create(String title, String body, String targetTypeRaw, UUID targetId,
                              int signatureGoal, Instant deadline, UUID creatorPublicId) {
        PetitionTargetType targetType = parseTargetType(targetTypeRaw);

        // D16 conflict-of-interest: the creator must not be the target (no petitioning against yourself).
        // isNotSelf compares the target public id against the caller; false => self-action.
        if (!scopeGuard.isNotSelf(targetId)) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTHZ_SELF_ACTION_BLOCKED, AuditOutcome.DENIED)
                    .actor(creatorPublicId)
                    .subject(targetId)
                    .reason("PETITION_AGAINST_SELF")
                    .build());
            throw new ApiException(ErrorCode.CONFLICT_OF_INTEREST);
        }

        // TODO(wiring): resolve creatorPublicId (account) -> identity Profile public id, and validate
        // targetId against the institutions registry, once those modules are wired (cross-module step).
        Petition petition = Petition.create(title, body, targetType, targetId,
                signatureGoal, deadline, creatorPublicId, null);
        petitions.save(petition);
        // SEARCH (ADR-0017 §1, ADR-0013 §1): keep the discovery projection in step with the petition's public
        // visibility. A freshly-created petition is DRAFT, so reindexForDiscovery REMOVES (idempotent no-op on
        // a never-indexed row) — a draft is NEVER discoverable (the no-leak fence, PRD §18). It is upserted only
        // once moderation moves it to ACTIVE (the activate path), which routes through this same single helper.
        reindexForDiscovery(petition);
        return mapper.toPetitionDto(petition);
    }

    /**
     * Signs a petition — the <b>binding civic act</b> (UC-E03).
     *
     * <p><b>Integrity fence (D18, PRD §23.5).</b> This method's authorisation reads ONLY:</p>
     * <ol>
     *   <li><b>tier</b> — gated by {@code @RequiresTier("T3")} on the controller (live-resolved, MF-2);</li>
     *   <li><b>no-self-petition</b> — the signer may not be the petition's target (D13/D16);</li>
     *   <li><b>one-per-person</b> — pre-checked here and <b>guaranteed</b> by the DB unique constraint;</li>
     *   <li><b>electoral scope (two-tier — F1)</b> — for a {@code REPRESENTATIVE}-targeted petition, the
     *       signer must be an elector of the target rep's <b>seat</b>, by mandate: a constituency-MP is gated
     *       on the rep's constituency ({@link RepresentativeQueryApi#constituencyOf} ×
     *       {@link ElectoralScopeApi#isElectorOf}); a councillor/ward-exec on the rep's <b>ward</b>
     *       ({@link RepresentativeQueryApi#wardOf} × {@link ElectoralScopeApi#isElectorOfWard}); a
     *       special-seats/nominated rep has no geographic gate (PRD §22.6). A mismatch is
     *       {@link ErrorCode#OUT_OF_SCOPE}, audited. An {@code OFFICE} target carries no constituency/ward
     *       gate.</li>
     * </ol>
     * <p>It <b>never</b> reads a token balance — that is the fence (PRD §23.5). The signature count bump is
     * a derived counter, not a weight. On success a {@link AuditEventType#PETITION_SIGNED} event is appended
     * (refs only, no PII — R-4).</p>
     *
     * @param petitionPublicId the petition to sign.
     * @param signerPublicId   the authenticated signer's account public id (from {@code CurrentUser}, never the body).
     * @param comment          optional comment, or {@code null}.
     * @param publicSignature  whether the signer opts to be shown publicly (default private).
     * @return the updated {@link PetitionDto} (with the incremented count / possibly {@code SUCCEEDED}).
     * @throws ResourceNotFoundException if the petition does not exist.
     * @throws ApiException {@link ErrorCode#CONFLICT} if the petition is not ACTIVE, or the signer already
     *                      signed (one-per-person); {@link ErrorCode#CONFLICT_OF_INTEREST} if the signer is
     *                      the petition's target (D16).
     */
    public PetitionDto sign(UUID petitionPublicId, UUID signerPublicId, String comment,
                            boolean publicSignature) {
        Petition petition = require(petitionPublicId);

        // Only an ACTIVE petition collects signatures (a SUCCEEDED/CLOSED petition does not).
        if (petition.getStatus() != PetitionStatus.ACTIVE) {
            throw new ApiException(ErrorCode.CONFLICT);
        }

        // D16: a representative may not sign a petition that targets themselves.
        if (petition.getTargetType() == PetitionTargetType.REPRESENTATIVE
                && !scopeGuard.isNotSelf(petition.getTargetId())) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTHZ_SELF_ACTION_BLOCKED, AuditOutcome.DENIED)
                    .actor(signerPublicId)
                    .subject(petition.getTargetId())
                    .reason("SIGN_PETITION_AGAINST_SELF")
                    .build());
            throw new ApiException(ErrorCode.CONFLICT_OF_INTEREST);
        }

        // One-per-person FAST pre-check (the DB unique constraint is the hard guarantee under race).
        if (signatures.existsByPetition_PublicIdAndSignerProfileId(petitionPublicId, signerPublicId)) {
            throw new ApiException(ErrorCode.CONFLICT);
        }

        // FENCE: electoral scope (D13, two-tier — F1). A petition addressed to a REPRESENTATIVE is scoped to
        // that rep's electoral seat, keyed off the rep's MANDATE (resolved via institutions' published ports;
        // the signer's electoral location via identity's published ports — engagement imports neither
        // module's internals, ADR-0013):
        //   * constituency-mandate MP  -> signer must be an elector of the rep's CONSTITUENCY;
        //   * councillor / ward-exec   -> signer must be an elector of the rep's WARD (F1: previously skipped,
        //                                  letting anyone nationwide petition any councillor);
        //   * special-seats / nominated -> no geographic seat, no geographic gate (PRD §22.6).
        // An OFFICE-targeted petition carries no constituency/ward gate (F5 — national office scope, unchanged).
        // NOTE: token balance is NEVER consulted in this path (§23.5 fence).
        if (petition.getTargetType() == PetitionTargetType.REPRESENTATIVE
                && !isElectorOfRepSeat(signerPublicId, petition.getTargetId())) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTHZ_SCOPE_DENIED, AuditOutcome.DENIED)
                    .actor(signerPublicId)
                    .subject(petition.getTargetId())
                    .reason("SIGN_PETITION_OUT_OF_ELECTORAL_SCOPE")
                    .build());
            throw new ApiException(ErrorCode.OUT_OF_SCOPE);
        }

        PetitionSignature signature = PetitionSignature.of(petition, signerPublicId, comment, publicSignature);
        try {
            signatures.save(signature);
        } catch (DataIntegrityViolationException dup) {
            // Concurrent double-sign hit the unique index — the fence held; surface a clean conflict.
            throw new ApiException(ErrorCode.CONFLICT, dup);
        }
        petition.registerSignature();

        // R-4: append the PETITION_SIGNED success event — the most sensitive civic acts carry a complete
        // immutable trail (binding-success, not just denials). References/public-ids and a non-PII reason
        // only (the petition target type) — never the comment or any PII (PRD §18, PDPA, L-1).
        audit.record(AuditEvent.Builder
                .of(AuditEventType.PETITION_SIGNED, AuditOutcome.SUCCESS)
                .actor(signerPublicId)
                .subject(petition.getPublicId())
                .reason(petition.getTargetType().name())
                .build());

        // ANALYTICS (Appendix E, M15): emit a petition_signed civic-activity fact on the outbox in THIS
        // transaction; the analytics sink records it ASYNCHRONOUSLY, off the signer's path. The signer signs at
        // T3 (the controller's @RequiresTier gate), so tier is T3; activeRole is CITIZEN; the petition target
        // type is the controlled `outcome` code. Ids/codes ONLY — NO signer identity, NO comment (PRD §18,
        // PDPA, ADR-0014 §1). WHY this does NOT breach the fence (D18/§23.5): analytics is a passive side-record
        // emitted AFTER the binding act completes; it neither reads the token balance nor influences the
        // signature count — the count bump above is a derived counter, never a token-weighted value.
        outboxWriter.append(EventEnvelope.of(
                AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED,
                AnalyticsEventTypes.AGGREGATE_CIVIC_ACTIVITY,
                petition.getPublicId(),
                new CivicActivityRecorded(
                        AnalyticsEventTypes.PETITION_SIGNED,
                        Instant.now(),
                        null,                                 // actorRef: no pseudonymous hash resolved here
                        null,                                 // geoAreaId: petition is not ward-scoped here
                        null,                                 // categoryId: n/a
                        "T3",                                 // tier (string — petition-sign is a T3 binding act)
                        null,                                 // channel: not resolved at this layer
                        "CITIZEN",                            // activeRole name (string — NOT the analytics enum)
                        null,                                 // latencySeconds: n/a
                        null,                                 // breachType: n/a
                        petition.getTargetType().name()),     // outcome = REPRESENTATIVE / OFFICE (controlled vocab)
                Instant.now()));

        // SEARCH (ADR-0017 §1): a sign may flip the petition ACTIVE -> SUCCEEDED (both publicly visible), and it
        // bumps the signature count shown on the discovery row. Re-index here — an idempotent PUBLIC upsert that
        // keeps the projection fresh. WHY this does NOT breach the fence (D18/§23.5): the upsert is a passive
        // side-record emitted AFTER the binding act; it reads no token balance and never influences the count.
        reindexForDiscovery(petition);
        return mapper.toPetitionDto(petition);
    }

    /**
     * Marks a petition {@code ACTIVE} (post-moderation, UC-E02) and makes it discoverable.
     *
     * <p>WHY this lives here (not only on the entity): activation is the {@code DRAFT -> ACTIVE} visibility
     * change, so it is the moment the petition first becomes public-safe and must enter discovery. Routing it
     * through the single {@link #reindexForDiscovery(Petition)} fence keeps the index-vs-no-index decision in
     * one place (DRY), exactly as reporting re-indexes on every lifecycle move.</p>
     *
     * @param petitionPublicId the petition to activate.
     * @return the now-ACTIVE {@link PetitionDto}.
     * @throws ResourceNotFoundException if the petition does not exist.
     */
    public PetitionDto activate(UUID petitionPublicId) {
        Petition petition = require(petitionPublicId);
        petition.activate();
        // Now publicly visible (ACTIVE) → upsert the public projection into discovery (ADR-0017 §1).
        reindexForDiscovery(petition);
        return mapper.toPetitionDto(petition);
    }

    /**
     * Pushes (or removes) this petition's <b>public, PII-free</b> discovery projection in the search index
     * (ADR-0017 §1; ADR-0013 §1 owner→search). The single place the index-vs-no-index decision lives, so the
     * privacy fence is enforced once and cannot drift across the create/activate/sign call sites — mirroring
     * reporting's {@code reindexForDiscovery}.
     *
     * <p><b>The fence (PRD §18, ADR-0017 §1/§4):</b> a petition is indexed <b>only if</b> it is publicly
     * visible — anything past {@code DRAFT} ({@link Petition#isPubliclyVisible()}). A {@code DRAFT} is
     * <b>never</b> indexed, and any time a petition is, or becomes, non-public this calls
     * {@link SearchIndexApi#remove} so it is positively pulled from discovery (removing an absent row is an
     * idempotent no-op — defence-in-depth).</p>
     *
     * <p><b>What is pushed (public-display + opaque ids only — never PII):</b> the petition
     * {@link Petition#getTitle() title} as the discovery label and a short {@link #snippet(String) snippet} of
     * the body as the preview (both already public for a non-DRAFT petition). The signature tally is encoded
     * into the keywords so a search row reflects momentum without re-reading the aggregate. <b>Never</b> the
     * signer list, the creator id, or any PII. {@code authoredByAccountId} is the creator profile id, carried
     * solely for the search module's suspended-author visibility maintenance (ADR-0017 §3) — never returned.
     * No area/category facet is set: a petition is targeted at an institutions-module addressee by id, not a
     * ward/category, so those discovery facets are intentionally {@code null}.</p>
     *
     * @param petition the petition whose discovery projection is being maintained.
     */
    private void reindexForDiscovery(Petition petition) {
        if (!petition.isPubliclyVisible()) {
            // DRAFT (or any non-public state): ensure it is absent from discovery (idempotent remove).
            searchIndex.remove(SearchEntityType.PETITION, petition.getPublicId());
            return;
        }
        searchIndex.upsert(new SearchDocumentUpsert(
                SearchEntityType.PETITION,
                petition.getPublicId(),
                petition.getTitle(),
                // Swahili-first corpus: the citizen's free text serves both locales (no machine translation);
                // the FTS config is `simple` (no per-language stemming), so one snippet serves SW/EN inputs.
                snippet(petition.getBody()),
                snippet(petition.getBody()),
                // Keywords: the petition status as a searchable term (e.g. SUCCEEDED) — public, non-PII.
                petition.getStatus().name(),
                null,                                // areaId: a petition is addressee-targeted, not ward-scoped
                null,                                // categoryId: n/a for a petition
                SearchVisibility.PUBLIC,
                // authoredByAccountId: visibility-maintenance only (ADR-0017 §3); never returned. Null for an
                // org-authored petition, which is fine (an authorless row).
                petition.getCreatorProfileId()));
    }

    /**
     * Truncates citizen free text to a lean, index-safe discovery snippet ({@link #SEARCH_SNIPPET_MAX} chars),
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

    /**
     * Resolves whether the signer is an elector of the target representative's seat, dispatching on the
     * rep's mandate (F1, D13): a constituency-mandate MP is gated on the rep's constituency; a
     * councillor/ward-exec on the rep's ward; a genuinely seat-less rep (special-seats/nominated) carries no
     * geographic gate and is always allowed. Both ports throw {@code NOT_FOUND} for a non-existent rep.
     *
     * @param signerPublicId the authenticated signer's account public id.
     * @param targetId       the petition's target representative public id.
     * @return {@code true} if the signer may sign against this rep on the electoral-scope axis.
     */
    private boolean isElectorOfRepSeat(UUID signerPublicId, UUID targetId) {
        Optional<UUID> constituency = representativeQueryApi.constituencyOf(targetId);
        if (constituency.isPresent()) {
            return electoralScopeApi.isElectorOf(signerPublicId, constituency.get());
        }
        // No constituency: a councillor/ward-exec is ward-tier — gate on the ward (F1).
        Optional<UUID> ward = representativeQueryApi.wardOf(targetId);
        if (ward.isPresent()) {
            return electoralScopeApi.isElectorOfWard(signerPublicId, ward.get());
        }
        // Genuinely seat-less (special-seats / nominated) — no geographic electoral gate (PRD §22.6).
        return true;
    }

    /** Loads a petition by public id or throws a localised not-found. */
    private Petition require(UUID publicId) {
        return petitions.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("engagement.petition.notFound", publicId));
    }

    /** Parses the raw target type, mapping an unknown value to a clean {@link ErrorCode#BAD_REQUEST}. */
    private PetitionTargetType parseTargetType(String raw) {
        try {
            return PetitionTargetType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
    }
}
