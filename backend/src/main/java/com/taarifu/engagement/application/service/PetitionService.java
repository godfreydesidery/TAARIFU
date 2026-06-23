package com.taarifu.engagement.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
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

    /**
     * @param petitions              petition persistence port.
     * @param signatures             signature persistence port (one-per-person pre-check).
     * @param mapper                 entity→DTO mapper.
     * @param scopeGuard             the shared conflict-of-interest seam ({@code @taarifuAuthz}); supplies the
     *                               no-self-action check (D16).
     * @param representativeQueryApi institutions' published port resolving a target rep's constituency (D13).
     * @param electoralScopeApi      identity's published port checking the signer's electoral scope (D13).
     * @param audit                  append-only audit writer (binding-action + denial evidence, L-1).
     */
    public PetitionService(PetitionRepository petitions,
                           PetitionSignatureRepository signatures,
                           EngagementMapper mapper,
                           ScopeGuard scopeGuard,
                           RepresentativeQueryApi representativeQueryApi,
                           ElectoralScopeApi electoralScopeApi,
                           AuditEventService audit) {
        this.petitions = petitions;
        this.signatures = signatures;
        this.mapper = mapper;
        this.scopeGuard = scopeGuard;
        this.representativeQueryApi = representativeQueryApi;
        this.electoralScopeApi = electoralScopeApi;
        this.audit = audit;
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
     *   <li><b>electoral scope</b> — for a {@code REPRESENTATIVE}-targeted petition, the signer must be an
     *       elector of the target rep's constituency: resolved via {@link RepresentativeQueryApi} ×
     *       {@link ElectoralScopeApi} (D13); a mismatch is {@link ErrorCode#OUT_OF_SCOPE}, audited. An
     *       {@code OFFICE} target (or a constituency-less rep) carries no constituency gate.</li>
     * </ol>
     * <p>It <b>never</b> reads a token balance — that is the fence (PRD §23.5). The signature count bump is
     * a derived counter, not a weight.</p>
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

        // FENCE: electoral scope (D13). A petition addressed to a REPRESENTATIVE is constituency-scoped:
        // the signer must be an elector of that representative's constituency. The rep's constituency is
        // resolved via institutions' published port; the signer's single isElectoral constituency is
        // checked via identity's published port (ADR-0013 — engagement imports neither module's internals).
        // A rep with no constituency (councillor/special-seats/nominated), and an OFFICE-targeted petition,
        // carry no constituency gate. NOTE: token balance is NEVER consulted in this path (§23.5 fence).
        if (petition.getTargetType() == PetitionTargetType.REPRESENTATIVE) {
            Optional<UUID> targetConstituency = representativeQueryApi.constituencyOf(petition.getTargetId());
            if (targetConstituency.isPresent()
                    && !electoralScopeApi.isElectorOf(signerPublicId, targetConstituency.get())) {
                audit.record(AuditEvent.Builder
                        .of(AuditEventType.AUTHZ_SCOPE_DENIED, AuditOutcome.DENIED)
                        .actor(signerPublicId)
                        .subject(petition.getTargetId())
                        .reason("SIGN_PETITION_OUT_OF_ELECTORAL_SCOPE")
                        .build());
                throw new ApiException(ErrorCode.OUT_OF_SCOPE);
            }
        }

        PetitionSignature signature = PetitionSignature.of(petition, signerPublicId, comment, publicSignature);
        try {
            signatures.save(signature);
        } catch (DataIntegrityViolationException dup) {
            // Concurrent double-sign hit the unique index — the fence held; surface a clean conflict.
            throw new ApiException(ErrorCode.CONFLICT, dup);
        }
        petition.registerSignature();

        // NOTE: a dedicated PETITION_SIGNED audit type is requested centrally (see CENTRAL INTEGRATION
        // NEEDS) — the common AuditEventType enum is a shared file this module must not edit. Until it is
        // added, the binding-sign success path is not separately audited here; the denial paths above use
        // the existing AUTHZ_SELF_ACTION_BLOCKED type. No PII is ever written either way (PRD §18).

        return mapper.toPetitionDto(petition);
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
