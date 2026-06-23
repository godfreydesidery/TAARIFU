package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.ProfileLocation;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.VerificationRequest;
import com.taarifu.identity.domain.model.enums.IdType;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.model.enums.VerificationStatus;
import com.taarifu.identity.domain.model.enums.VerificationType;
import com.taarifu.identity.domain.repository.ProfileLocationRepository;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.VerificationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator (Moderator) verification queue + approve/reject — the first scoped staff workflow (Flow 3,
 * VERIFICATION-DESIGN §5; D13/D16/MF-2).
 *
 * <p>Responsibility: lists the PENDING ID queue narrowed to the caller's area scope, and decides a
 * request. <b>Approve</b> → {@code Profile.markIdVerified()} (which makes the <b>live</b> tier T3 via the
 * existing {@link TierService} — never a manual {@code setTrustTier(T3)}, MF-2) and, for a voter ID, sets
 * the authoritative {@code isElectoral} location (D13, bypassing the cooldown). <b>Reject</b> → reason
 * code; tier untouched. Every decision is audited with the active context (multi-hat, D16).</p>
 *
 * <p>Conflict-of-interest (D16) and scope (MF-3) are enforced at the controller via method security
 * ({@code isNotSelf} + {@code canActOnArea}); this service additionally re-derives the subject ward and
 * filters the queue by scope so a Moderator only ever sees what they may act on (deny-by-default).</p>
 */
@Service
public class VerificationReviewService {

    private final VerificationRequestRepository verificationRequestRepository;
    private final ProfileRepository profileRepository;
    private final ProfileLocationRepository profileLocationRepository;
    private final TierService tierService;
    private final LocationService locationService;
    private final ScopeGuard scopeGuard;
    private final AuditEventService audit;
    private final ClockPort clock;

    /**
     * @param verificationRequestRepository the queue store.
     * @param profileRepository             subject profile lookup (flip {@code idVerified}, read electoral ward).
     * @param profileLocationRepository     the subject's locations (scope ward + voter-ID fallback ward).
     * @param tierService                   live tier recompute + cache (MF-2 — never a manual setter).
     * @param locationService               sets the authoritative electoral on a voter-ID approval (D13).
     * @param scopeGuard                    area-scope filter for the queue (MF-3).
     * @param audit                         append-only audit writer (decision + tier + electoral — L-1).
     * @param clock                         time source for decision/verify stamps (testable).
     */
    public VerificationReviewService(VerificationRequestRepository verificationRequestRepository,
                                     ProfileRepository profileRepository,
                                     ProfileLocationRepository profileLocationRepository,
                                     TierService tierService,
                                     LocationService locationService,
                                     ScopeGuard scopeGuard,
                                     AuditEventService audit,
                                     ClockPort clock) {
        this.verificationRequestRepository = verificationRequestRepository;
        this.profileRepository = profileRepository;
        this.profileLocationRepository = profileLocationRepository;
        this.tierService = tierService;
        this.locationService = locationService;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Lists the PENDING ID queue, filtered to the items whose subject's electoral/primary ward is within
     * the caller's area scope (deny-by-default; an unrestricted-within-role grant sees all — §5).
     *
     * @return the scoped queue items (subject + ID type + submitted-at + evidence ref; no PII).
     */
    @Transactional(readOnly = true)
    public List<QueueItem> listQueue() {
        return verificationRequestRepository
                .findByStatusAndTypeOrderByIdDesc(VerificationStatus.PENDING, VerificationType.ID).stream()
                .filter(this::inCallerScope)
                .map(this::toItem)
                .toList();
    }

    /**
     * Approves a PENDING ID request → the subject becomes <b>live</b> T3; a voter ID sets the
     * authoritative electoral location (D13).
     *
     * @param reviewerPublicId   the deciding Moderator's public id (multi-hat audit, D16).
     * @param verificationPublicId the request to approve.
     * @param registeredWardPublicId the ward the voter ID is registered to (operator reads it from the
     *                                card); {@code null} falls back to the subject's primary ward. Ignored
     *                                for non-voter IDs.
     * @param note               an optional operator note.
     * @return the decision result (status + the subject's new live tier).
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if the request/profile is missing;
     *                      {@link ErrorCode#CONFLICT} if it is not a PENDING ID request, or a voter-ID
     *                      approval has no registered ward to resolve.
     */
    @Transactional
    public DecisionResult approve(UUID reviewerPublicId, UUID verificationPublicId,
                                  UUID registeredWardPublicId, String note) {
        VerificationRequest request = requirePendingId(verificationPublicId);
        Profile profile = profileRepository.findByUser(request.getSubject())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        request.approve(reviewerPublicId, note, clock.now());
        profile.markIdVerified(clock.now());

        if (profile.getIdType() == IdType.VOTER) {
            UUID wardPublicId = resolveRegisteredWard(profile, registeredWardPublicId);
            // Voter-ID-authoritative electoral set; bypasses the cooldown, overrides any manual electoral.
            locationService.setElectoralAuthoritative(profile, wardPublicId);
        }

        // Live tier recompute (T3 now that idVerified is true) + cache the hint on the user (MF-2).
        TrustTier liveTier = recacheTier(profile);

        UUID subjectPublicId = request.getSubject().getPublicId();
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_VERIFICATION_APPROVED, AuditOutcome.SUCCESS)
                .actor(reviewerPublicId).subject(subjectPublicId)
                .roles("MODERATOR").reason(profile.getIdType() == null ? "ID" : profile.getIdType().name())
                .build());
        return new DecisionResult(VerificationStatus.APPROVED, liveTier);
    }

    /**
     * Rejects a PENDING ID request with a reason code; the subject's tier is untouched.
     *
     * @param reviewerPublicId   the deciding Moderator's public id.
     * @param verificationPublicId the request to reject.
     * @param reasonCode         the machine rejection reason.
     * @param note               an optional operator note.
     * @return the decision result (status REJECTED; tier as-is).
     * @throws ApiException {@link ErrorCode#NOT_FOUND}/{@link ErrorCode#CONFLICT} as for approve.
     */
    @Transactional
    public DecisionResult reject(UUID reviewerPublicId, UUID verificationPublicId,
                                 String reasonCode, String note) {
        VerificationRequest request = requirePendingId(verificationPublicId);
        request.reject(reviewerPublicId, reasonCode, note, clock.now());
        UUID subjectPublicId = request.getSubject().getPublicId();
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_VERIFICATION_REJECTED, AuditOutcome.SUCCESS)
                .actor(reviewerPublicId).subject(subjectPublicId)
                .roles("MODERATOR").reason(reasonCode).build());
        // Tier unchanged on rejection; recompute only to keep the cached hint consistent (no-op if same).
        Profile profile = profileRepository.findByUser(request.getSubject()).orElse(null);
        TrustTier tier = profile == null ? request.getSubject().getTrustTier() : recacheTier(profile);
        return new DecisionResult(VerificationStatus.REJECTED, tier);
    }

    /** Recomputes the live tier, audits a transition if it moved, caches the hint on the user (MF-2). */
    private TrustTier recacheTier(Profile profile) {
        User user = profile.getUser();
        TrustTier before = user.getTrustTier();
        TrustTier after = tierService.resolveLiveTier(profile);
        if (before != after) {
            user.setTrustTier(after);
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_TIER_CHANGED, AuditOutcome.SUCCESS)
                    .actor(user.getPublicId()).subject(user.getPublicId())
                    .reason(before.name() + "->" + after.name()).build());
        }
        return after;
    }

    /** Loads a request and asserts it is a PENDING ID request (the only decidable state). */
    private VerificationRequest requirePendingId(UUID verificationPublicId) {
        VerificationRequest request = verificationRequestRepository.findByPublicId(verificationPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (request.getType() != VerificationType.ID || request.getStatus() != VerificationStatus.PENDING) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        return request;
    }

    /** Resolves the voter-ID registered ward: the operator-supplied one, else the subject's primary ward. */
    private UUID resolveRegisteredWard(Profile profile, UUID registeredWardPublicId) {
        if (registeredWardPublicId != null) {
            return registeredWardPublicId;
        }
        return profileLocationRepository.findByProfileAndPrimaryTrue(profile)
                .map(pl -> pl.getWard().getPublicId())
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT));
    }

    /** @return whether the request's subject ward is within the caller's area scope (MF-3, deny-by-default). */
    private boolean inCallerScope(VerificationRequest request) {
        UUID ward = subjectWard(request).orElse(null);
        // No ward to scope on (a citizen with no pin) is only visible to an unrestricted-within-role caller.
        return ward != null && scopeGuard.canActOnArea(ward);
    }

    /** The subject's electoral ward if set, else their primary ward — the unit used for scope matching. */
    private Optional<UUID> subjectWard(VerificationRequest request) {
        Profile profile = profileRepository.findByUser(request.getSubject()).orElse(null);
        if (profile == null) {
            return Optional.empty();
        }
        Optional<ProfileLocation> electoral = profileLocationRepository.findByProfileAndElectoralTrue(profile);
        if (electoral.isPresent()) {
            return electoral.map(pl -> pl.getWard().getPublicId());
        }
        return profileLocationRepository.findByProfileAndPrimaryTrue(profile)
                .map(pl -> pl.getWard().getPublicId());
    }

    private QueueItem toItem(VerificationRequest r) {
        return new QueueItem(r.getPublicId(), r.getSubject().getPublicId(),
                r.getType().name(), r.getCreatedAt(), r.getEvidenceRef());
    }

    /**
     * Resolves the subject's ward for a request (used by the controller's scope guard helper).
     *
     * @param verificationPublicId the request public id.
     * @return the subject's electoral/primary ward public id, or empty if none/not found.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> wardOf(UUID verificationPublicId) {
        return verificationRequestRepository.findByPublicId(verificationPublicId)
                .flatMap(this::subjectWard);
    }

    /**
     * Resolves the subject account public id for a request (used by the controller's {@code isNotSelf}
     * conflict-of-interest guard, D16).
     *
     * @param verificationPublicId the request public id.
     * @return the subject account's public id, or empty if not found.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> subjectOf(UUID verificationPublicId) {
        return verificationRequestRepository.findByPublicId(verificationPublicId)
                .map(r -> r.getSubject().getPublicId());
    }

    /**
     * A queue row (no PII; references only).
     *
     * @param verificationPublicId the request public id.
     * @param subjectPublicId      the citizen being verified.
     * @param idType               the kind of verification (always {@code ID} here).
     * @param submittedAt          when the request was created.
     * @param evidenceRef          the object-store evidence key, or {@code null}.
     */
    public record QueueItem(UUID verificationPublicId, UUID subjectPublicId, String idType,
                            java.time.Instant submittedAt, String evidenceRef) {
    }

    /**
     * A decision outcome.
     *
     * @param status      the new request status.
     * @param subjectTier the subject's resulting live tier.
     */
    public record DecisionResult(VerificationStatus status, TrustTier subjectTier) {
    }
}
