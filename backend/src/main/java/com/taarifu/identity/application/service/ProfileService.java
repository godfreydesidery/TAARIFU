package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.geography.application.service.GeographyQueryService;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.ProfileLocation;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.AssociationType;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.repository.ProfileLocationRepository;
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
 * the profile — names/demographics and at least one pinned {@link ProfileLocation}. After every
 * tier-affecting change it recomputes the live tier via {@link TierService} and caches it on the
 * {@link User} (the cache is a token hint only — gating always re-resolves live, MF-2). It never returns
 * another user's profile or any {@code idNo}/location of others, and never logs PII values (S-4).</p>
 *
 * <p>The single-primary rule (D12) is upheld here: pinning a new primary first clears any existing
 * primary (the DB partial-unique index is the hard backstop).</p>
 */
@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ProfileLocationRepository profileLocationRepository;
    private final GeographyQueryService geographyQueryService;
    private final TierService tierService;
    private final AuditEventService audit;

    /**
     * @param userRepository            account lookup.
     * @param profileRepository         profile persistence.
     * @param profileLocationRepository location persistence (private PII).
     * @param geographyQueryService     geography public API for ward→constituency pin resolution.
     * @param tierService               live tier recompute + cache.
     * @param audit                     append-only audit writer.
     */
    public ProfileService(UserRepository userRepository,
                          ProfileRepository profileRepository,
                          ProfileLocationRepository profileLocationRepository,
                          GeographyQueryService geographyQueryService,
                          TierService tierService,
                          AuditEventService audit) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.profileLocationRepository = profileLocationRepository;
        this.geographyQueryService = geographyQueryService;
        this.tierService = tierService;
        this.audit = audit;
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
     * Pins a ward location to the caller's profile (PRIVATE PII). Resolves the ward + effective
     * constituency through the geography public API; if {@code primary} is set, clears any existing
     * primary first (single-primary, D12). Recomputes the live tier (the ≥1-pin half of T2).
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
        User user = requireUser(userPublicId);
        Profile profile = profileRepository.findByUser(user)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        GeographyQueryService.WardPin pin = geographyQueryService.resolveWardPin(wardPublicId);

        if (primary) {
            // Single-primary (D12): demote any existing primary before setting the new one.
            profileLocationRepository.findByProfileAndPrimaryTrue(profile)
                    .ifPresent(ProfileLocation::clearPrimary);
        }
        profileLocationRepository.save(
                ProfileLocation.pin(profile, pin.ward(), pin.constituency(), associationType, primary));

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
