package com.taarifu.identity.application.service;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.CryptoPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.VerificationRequest;
import com.taarifu.identity.domain.model.enums.IdType;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.model.enums.VerificationStatus;
import com.taarifu.identity.domain.model.enums.VerificationType;
import com.taarifu.identity.domain.port.IdentityVerificationProvider;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import com.taarifu.identity.domain.repository.VerificationRequestRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Citizen-facing ID/voter verification submit → a {@link VerificationStatus#PENDING} request (Flow 2,
 * VERIFICATION-DESIGN §4; D15/D-Q2).
 *
 * <p>Responsibility: a T2 citizen submits a government ID. This service: (1) computes the
 * <b>blind-index</b> over {@code idType + ":" + idNo} and rejects a second account on the same ID
 * (D15 → {@code DUPLICATE_IDENTITY}); (2) records the identity on the {@link Profile} (the {@code idNo}
 * is field-encrypted, never logged — S-4); (3) routes through the {@link IdentityVerificationProvider}
 * port (operator-assisted by default → {@code PENDING_REVIEW}); (4) creates the {@code PENDING} request;
 * (5) audits {@code AUTH_VERIFICATION_REQUESTED} with a hash reference, never the {@code idNo}.</p>
 *
 * <p>Submitting is <b>not</b> being verified: the tier is unchanged here ({@code idVerified} stays false)
 * — a pending review never grants or removes tier (PRD §25.5). Idempotency: a second submit while one is
 * {@code PENDING} for the same subject+type returns the existing request (no duplicate queue entries).</p>
 */
@Service
public class VerificationService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final VerificationRequestRepository verificationRequestRepository;
    private final IdentityVerificationProvider verificationProvider;
    private final CryptoPort crypto;
    private final AuditEventService audit;
    private final IdentityFunnelAnalytics funnel;

    /**
     * @param userRepository                 account lookup.
     * @param profileRepository              profile persistence + dedup blind-index query (D15).
     * @param verificationRequestRepository  the queue store (idempotency + create).
     * @param verificationProvider           the pluggable verification port (operator-assisted default, D-Q2).
     * @param crypto                         computes the blind index (never decrypts for dedup — D15).
     * @param audit                          append-only audit writer (refs/hashes only — L-1/§18).
     * @param funnel                         emits the {@code identity_verification_started}/{@code _failed}
     *                                       verification-funnel facts (A1; channel APP).
     */
    public VerificationService(UserRepository userRepository,
                               ProfileRepository profileRepository,
                               VerificationRequestRepository verificationRequestRepository,
                               IdentityVerificationProvider verificationProvider,
                               CryptoPort crypto,
                               AuditEventService audit,
                               IdentityFunnelAnalytics funnel) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.verificationRequestRepository = verificationRequestRepository;
        this.verificationProvider = verificationProvider;
        this.crypto = crypto;
        this.audit = audit;
        this.funnel = funnel;
    }

    /**
     * Submits a government ID for verification (the path to T3).
     *
     * @param userPublicId the authenticated, ≥T2 citizen.
     * @param idType       the ID document type (NIDA/voter/passport).
     * @param idNo         the document number (PII — encrypted on store, never logged).
     * @param fullName     the claimed name to match against the ID (not persisted as new PII; not logged).
     * @param evidenceRef  object-store key to the submitted evidence, or {@code null} (out-of-band).
     * @return the created (or already in-flight) request's public id + status.
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if the account/profile is missing;
     *                      {@link ErrorCode#DUPLICATE_IDENTITY} if another account already holds this ID (D15).
     */
    @Transactional
    public SubmitResult submitIdVerification(UUID userPublicId, IdType idType, String idNo,
                                             String fullName, String evidenceRef) {
        User user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Profile profile = profileRepository.findByUser(user)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        // Idempotency: an in-flight PENDING request for the same subject+type returns as-is (§4).
        var existing = verificationRequestRepository
                .findFirstBySubjectAndTypeAndStatus(user, VerificationType.ID, VerificationStatus.PENDING);
        if (existing.isPresent()) {
            return new SubmitResult(existing.get().getPublicId(), VerificationStatus.PENDING);
        }

        // D15: dedup by deterministic blind index — never decrypts any ID.
        String idHash = crypto.blindIndex(idType.name() + ":" + normalise(idNo));
        if (profileRepository.existsByIdHashAndUserNot(idHash, user)) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_VERIFICATION_REQUESTED, AuditOutcome.DENIED)
                    .actor(userPublicId).subject(userPublicId)
                    .reason("DUPLICATE_IDENTITY").detailRef("idHash:" + idHash).build());
            // ANALYTICS (A1, §3.3 funnel): a dedup-blocked submit is a funnel drop-off (the subject does not
            // reach T3). Emitted on the outbox in THIS transaction; the rollback below would NOT discard it —
            // but a dedup block does not roll back (we throw cleanly), so the fact is durable. No PII (no idNo,
            // no hash, no subject) — the tier stays T2 (the verification never proceeded).
            funnel.emit(AnalyticsEventTypes.IDENTITY_VERIFICATION_FAILED, TrustTier.T2.name(),
                    IdentityFunnelAnalytics.CHANNEL_APP);
            throw new ApiException(ErrorCode.DUPLICATE_IDENTITY);
        }

        // Record the identity (idNo encrypted by the converter; not verified yet — tier unchanged).
        profile.setIdentity(idType, idNo, idHash);

        // Route through the port; the operator-assisted default returns PENDING_REVIEW (D-Q2).
        verificationProvider.verify(idType.name(), idNo, fullName);

        VerificationRequest request = VerificationRequest.submit(user, VerificationType.ID, evidenceRef);
        try {
            verificationRequestRepository.saveAndFlush(request);
            profileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            // DB unique index ux_profile_id_hash is the hard backstop against a concurrent duplicate (D15).
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_VERIFICATION_REQUESTED, AuditOutcome.DENIED)
                    .actor(userPublicId).subject(userPublicId)
                    .reason("DUPLICATE_IDENTITY_RACE").detailRef("idHash:" + idHash).build());
            throw new ApiException(ErrorCode.DUPLICATE_IDENTITY);
        }

        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_VERIFICATION_REQUESTED, AuditOutcome.SUCCESS)
                .actor(userPublicId).subject(userPublicId)
                .reason(idType.name()).detailRef("idHash:" + idHash).build());

        // ANALYTICS (A1, §3.3 funnel): the T2→(T3) funnel step — a verification was started. Emitted on the
        // outbox in THIS transaction, recorded asynchronously off the citizen path. The subject is at T2 here
        // (submitting is not being verified — the tier is unchanged, PRD §25.5). Coarse tier+channel only, no
        // PII (no idNo, no idHash, no fullName, no evidence ref on the event).
        funnel.emit(AnalyticsEventTypes.IDENTITY_VERIFICATION_STARTED, TrustTier.T2.name(),
                IdentityFunnelAnalytics.CHANNEL_APP);

        return new SubmitResult(request.getPublicId(), VerificationStatus.PENDING);
    }

    /** Normalises an ID number for hashing so trivial formatting differences cannot dodge dedup (D15). */
    private static String normalise(String idNo) {
        return idNo == null ? "" : idNo.trim().replaceAll("\\s", "").toUpperCase();
    }

    /**
     * The result of a verification submit.
     *
     * @param verificationPublicId the created (or in-flight) request's public id.
     * @param status               the request status (always {@code PENDING} on submit).
     */
    public record SubmitResult(UUID verificationPublicId, VerificationStatus status) {
    }
}
