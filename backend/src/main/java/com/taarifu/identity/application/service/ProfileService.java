package com.taarifu.identity.application.service;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.AssociationType;
import com.taarifu.identity.domain.model.enums.OtpPurpose;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Profile read + completion path to trust-tier T2 (AUTH-DESIGN §6, ADR-0011 §1).
 *
 * <p>Responsibility: serves the caller's own profile ({@code GET /me}) and drives T1→T2 by completing
 * the profile — names/demographics, the email-OTP contact-verify channel, and (via
 * {@link LocationService}) at least one pinned location. After every tier-affecting change it recomputes
 * the live tier via {@link TierService} and caches it on the {@link User} (the cache is a token hint
 * only — gating always re-resolves live, MF-2). It never returns another user's profile or any
 * {@code idNo}/location of others, and never logs PII values (S-4).</p>
 *
 * <p>The {@code ProfileLocation} lifecycle (add/remove/set-primary/set-electoral, D12/D13) lives in
 * {@link LocationService} (SRP, VERIFICATION-DESIGN §2); {@link #pinLocation} delegates to it.</p>
 */
@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final TierService tierService;
    private final OtpService otpService;
    private final LocationService locationService;
    private final AuditEventService audit;
    private final IdentityFunnelAnalytics funnel;

    /**
     * @param userRepository    account lookup.
     * @param profileRepository profile persistence.
     * @param tierService       live tier recompute + cache.
     * @param otpService        OTP issue/verify (the EMAIL VERIFY channel for T2 contact verification).
     * @param locationService   {@code ProfileLocation} lifecycle (SRP — pin/remove/primary/electoral).
     * @param audit             append-only audit writer.
     * @param funnel            emits the {@code profile_completed} verification-funnel fact on T1→T2 (A1).
     */
    public ProfileService(UserRepository userRepository,
                          ProfileRepository profileRepository,
                          TierService tierService,
                          OtpService otpService,
                          LocationService locationService,
                          AuditEventService audit,
                          IdentityFunnelAnalytics funnel) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.tierService = tierService;
        this.otpService = otpService;
        this.locationService = locationService;
        this.audit = audit;
        this.funnel = funnel;
    }

    /**
     * Returns the caller's own profile snapshot ({@code GET /me}).
     *
     * @param userPublicId the authenticated account public id.
     * @return a non-PII-leaking view of the caller's account + profile + live tier.
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if the account/profile is missing.
     */
    @Transactional(readOnly = true)
    public MeView getMe(UUID userPublicId) {
        User user = requireUser(userPublicId);
        Profile profile = profileRepository.findByUser(user)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        TrustTier liveTier = tierService.resolveLiveTier(profile);
        return new MeView(
                user.getPublicId(),
                user.getPhone(),
                profile.getFirstName(),
                profile.getLastName(),
                user.getEmail(),
                liveTier.name(),
                profile.isPhoneVerified(),
                profile.isEmailVerified(),
                profile.isIdVerified());
    }

    /**
     * Completes profile name/demographic fields (PATCH semantics — {@code null} leaves a field).
     * Recomputes + caches the live tier (may promote to T2 once name + ≥1 location are present).
     *
     * @param userPublicId the authenticated account.
     * @param firstName    given/first name, or {@code null}.
     * @param lastName     family name, or {@code null}.
     * @param dateOfBirth  date of birth, or {@code null}.
     * @param gender       gender, or {@code null}.
     * @param nationality  nationality code, or {@code null}.
     * @return the recomputed live tier.
     */
    @Transactional
    public TrustTier updateProfile(UUID userPublicId, String firstName, String lastName,
                                   LocalDate dateOfBirth, String gender, String nationality) {
        User user = requireUser(userPublicId);
        Profile profile = profileRepository.findByUser(user)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        profile.updateDetails(firstName, lastName, dateOfBirth, gender, nationality);
        // Audit field NAMES only — never the values (S-4).
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_TIER_CHANGED, AuditOutcome.SUCCESS)
                .actor(userPublicId).subject(userPublicId).reason("PROFILE_UPDATED").build());
        return recomputeTier(user, profile);
    }

    /**
     * Pins a ward location to the caller's profile (the ≥1-pin half of T2). Delegates the
     * {@code ProfileLocation} lifecycle to {@link LocationService} (SRP, VERIFICATION-DESIGN §2) and
     * recomputes the live tier afterwards.
     *
     * @param userPublicId    the authenticated account.
     * @param wardPublicId    the ward to pin (minimum granularity, PRD §9.0).
     * @param associationType how the profile relates to the place.
     * @param primary         whether this is the single primary (default-context) location.
     * @return the recomputed live tier.
     */
    @Transactional
    public TrustTier pinLocation(UUID userPublicId, UUID wardPublicId,
                                 AssociationType associationType, boolean primary) {
        locationService.addLocation(userPublicId, wardPublicId, associationType, primary);
        User user = requireUser(userPublicId);
        Profile profile = profileRepository.findByUser(user)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return recomputeTier(user, profile);
    }

    /**
     * Requests an EMAIL VERIFY OTP for the caller's contact-channel verification (T2 path,
     * VERIFICATION-DESIGN §3). The email is recorded on the account so the verified channel can be
     * matched on verify. Returns the challenge id (never reveals delivery details; the code is never
     * logged — S-4).
     *
     * @param userPublicId the authenticated account.
     * @param email        the destination email to verify.
     * @return the OTP challenge public id.
     */
    @Transactional
    public UUID requestEmailVerification(UUID userPublicId, String email) {
        User user = requireUser(userPublicId);
        // Record the email so verify can mark the channel; not unique (login alias semantics, see User).
        user.setEmail(email);
        return otpService.issueEmail(email, OtpPurpose.VERIFY, user);
    }

    /**
     * Verifies an EMAIL VERIFY OTP → marks the profile's email verified → recomputes the live tier (may
     * promote to T2 once name + ≥1 location are present). The client cannot self-assert T2 (MF-2).
     *
     * @param userPublicId the authenticated account.
     * @param challengeId  the EMAIL VERIFY challenge id.
     * @param code         the code the user entered.
     * @return the recomputed live tier.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} on a bad/expired/wrong code.
     */
    @Transactional
    public TrustTier verifyEmail(UUID userPublicId, UUID challengeId, String code) {
        User user = requireUser(userPublicId);
        Profile profile = profileRepository.findByUser(user)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        // verify() consumes the challenge and throws on any bad/expired/wrong-code path (audited there).
        otpService.verify(challengeId, code, OtpPurpose.VERIFY);
        profile.markEmailVerified();
        return recomputeTier(user, profile);
    }

    /**
     * Recomputes + caches the caller's live tier (used after an out-of-band location change so the client
     * sees a T2 promotion immediately). Server-computed; never client-asserted (MF-2).
     *
     * @param userPublicId the authenticated account.
     * @return the recomputed live tier.
     */
    @Transactional
    public TrustTier getMeTier(UUID userPublicId) {
        User user = requireUser(userPublicId);
        Profile profile = profileRepository.findByUser(user)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return recomputeTier(user, profile);
    }

    /** Recomputes the live tier, caches it on the user, and audits a transition if it changed. */
    private TrustTier recomputeTier(User user, Profile profile) {
        TrustTier before = user.getTrustTier();
        TrustTier after = tierService.resolveLiveTier(profile);
        if (before != after) {
            user.setTrustTier(after);
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_TIER_CHANGED, AuditOutcome.SUCCESS)
                    .actor(user.getPublicId()).subject(user.getPublicId())
                    .reason(before.name() + "->" + after.name()).build());
            // ANALYTICS (A1, §3.3 funnel): the T1→T2 step — emitted only on the genuine RISE to T2 (this is the
            // single point every T2-affecting profile change funnels through: name/email/location). Guarding on
            // an upward transition (before < T2 <= after) avoids re-emitting on a no-op recompute or a downgrade
            // (§25.5). Channel APP — the profile-completion path is app/web. Coarse tier only, no PII.
            if (after == TrustTier.T2 && before.compareTo(TrustTier.T2) < 0) {
                funnel.emit(AnalyticsEventTypes.PROFILE_COMPLETED, after.name(),
                        IdentityFunnelAnalytics.CHANNEL_APP);
            }
        }
        return after;
    }

    private User requireUser(UUID userPublicId) {
        return userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /**
     * The caller's own profile view ({@code GET /me}). Carries the caller's <b>own</b> phone/email (it is
     * the owner reading themselves); it never carries {@code idNo} or any location/PII of others.
     *
     * @param userPublicId   the account public id.
     * @param phone          the caller's own phone (owner-readable; never logged).
     * @param firstName      given/first name, or {@code null}.
     * @param lastName       family name, or {@code null}.
     * @param email          the caller's own email, or {@code null}.
     * @param tier           the live trust tier name (UI hint; gating re-resolves live).
     * @param phoneVerified  whether phone is verified.
     * @param emailVerified  whether email is verified.
     * @param idVerified     whether the government ID is verified.
     */
    public record MeView(UUID userPublicId, String phone, String firstName, String lastName, String email,
                         String tier, boolean phoneVerified, boolean emailVerified, boolean idVerified) {
    }
}
