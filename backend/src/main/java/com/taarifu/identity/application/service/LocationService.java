package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.geography.application.service.GeographyQueryService;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.ProfileLocation;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.AssociationType;
import com.taarifu.identity.domain.model.enums.IdType;
import com.taarifu.identity.domain.repository.ProfileLocationRepository;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The {@code ProfileLocation} lifecycle — add/remove, set the single primary, set the single electoral
 * (PRD §9.0, D12/D13; VERIFICATION-DESIGN §6). Extracted from {@code ProfileService} (SRP).
 *
 * <p>All {@code ProfileLocation} rows are <b>private PII</b> — no public DTO, never logged (§18). Two
 * database-enforced singletons are upheld here, with the partial-unique indexes as the hard backstops
 * against a race:</p>
 * <ul>
 *   <li><b>Single primary</b> (D12): setting a new primary demotes the prior one in the same transaction.</li>
 *   <li><b>Single electoral</b> (D13): voter-ID-authoritative. A <b>manual</b> change is cooldown-guarded
 *       (default ~6 months, config); a <b>voter-ID-authoritative</b> set (from the approval flow) bypasses
 *       the cooldown and overrides any manual electoral. A profile whose electoral was set
 *       authoritatively cannot be moved manually — it must be re-verified.</li>
 * </ul>
 *
 * <p>WHY the cooldown reads the durable {@code electoral_changed_at} column (not Redis): it must survive
 * a restart/cache flush by design (§1.2). The clock is injected for testability.</p>
 */
@Service
public class LocationService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ProfileLocationRepository profileLocationRepository;
    private final GeographyQueryService geographyQueryService;
    private final AuditEventService audit;
    private final ClockPort clock;
    private final Duration electoralCooldown;

    /**
     * @param userRepository            account lookup.
     * @param profileRepository         profile lookup.
     * @param profileLocationRepository location persistence (private PII).
     * @param geographyQueryService     geography public API for ward→constituency pin resolution.
     * @param audit                     append-only audit writer (electoral changes are audited — D13/L-1).
     * @param clock                     time source for the cooldown (testable — never {@code Instant.now()}).
     * @param cooldownDays              the manual {@code isElectoral} change cooldown in days
     *                                  (config {@code taarifu.identity.electoral.cooldown-days}, ~6 months).
     */
    public LocationService(UserRepository userRepository,
                           ProfileRepository profileRepository,
                           ProfileLocationRepository profileLocationRepository,
                           GeographyQueryService geographyQueryService,
                           AuditEventService audit,
                           ClockPort clock,
                           @Value("${taarifu.identity.electoral.cooldown-days:183}") long cooldownDays) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.profileLocationRepository = profileLocationRepository;
        this.geographyQueryService = geographyQueryService;
        this.audit = audit;
        this.clock = clock;
        this.electoralCooldown = Duration.ofDays(cooldownDays);
    }

    /**
     * Adds a ward pin to the caller's profile, resolving the admin chain + effective constituency through
     * geography. If {@code primary} is set, demotes any prior primary first (single-primary, D12).
     *
     * @param userPublicId    the authenticated account.
     * @param wardPublicId    the ward to pin (minimum granularity, PRD §9.0).
     * @param associationType how the profile relates to the place.
     * @param primary         whether this becomes the single primary (default-context) location.
     * @return the new location's public id.
     */
    @Transactional
    public UUID addLocation(UUID userPublicId, UUID wardPublicId,
                            AssociationType associationType, boolean primary) {
        Profile profile = requireProfile(userPublicId);
        GeographyQueryService.WardPin pin = geographyQueryService.resolveWardPin(wardPublicId);
        if (primary) {
            profileLocationRepository.findByProfileAndPrimaryTrue(profile)
                    .ifPresent(ProfileLocation::clearPrimary);
        }
        ProfileLocation saved = profileLocationRepository.save(
                ProfileLocation.pin(profile, pin.ward(), pin.constituency(), associationType, primary));
        return saved.getPublicId();
    }

    /**
     * Soft-deletes one of the caller's locations.
     *
     * @param userPublicId     the authenticated account.
     * @param locationPublicId the location to remove (must belong to the caller).
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if the location is not the caller's;
     *                      {@link ErrorCode#CONFLICT} if it is the only location of a T2+ user (would break
     *                      the ≥1-pin T2 predicate) or the authoritatively-set electoral (re-verify to move).
     */
    @Transactional
    public void removeLocation(UUID userPublicId, UUID locationPublicId) {
        Profile profile = requireProfile(userPublicId);
        ProfileLocation location = requireOwnedLocation(profile, locationPublicId);

        if (location.isElectoral() && isElectoralAuthoritative(profile)) {
            // The voter-ID-authoritative electoral cannot be silently dropped — re-verify to move it (§6).
            throw new ApiException(ErrorCode.CONFLICT);
        }
        if (profileLocationRepository.countByProfile(profile) <= 1) {
            // Removing the last pin would drop a T2+ citizen below the ≥1-pin predicate (§6).
            throw new ApiException(ErrorCode.CONFLICT);
        }
        location.markDeleted(userPublicId);
    }

    /**
     * Sets a location as the single primary (default context), demoting any prior primary (D12).
     *
     * @param userPublicId     the authenticated account.
     * @param locationPublicId the location to promote.
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if the location is not the caller's.
     */
    @Transactional
    public void setPrimary(UUID userPublicId, UUID locationPublicId) {
        Profile profile = requireProfile(userPublicId);
        ProfileLocation target = requireOwnedLocation(profile, locationPublicId);
        profileLocationRepository.findByProfileAndPrimaryTrue(profile)
                .filter(existing -> !existing.getPublicId().equals(target.getPublicId()))
                .ifPresent(ProfileLocation::clearPrimary);
        target.markPrimary();
    }

    /**
     * <b>Manual</b> set of the single electoral location (D13, §25.4). Allowed only when the profile's
     * electoral was <b>not</b> set authoritatively by a voter ID, and only after the cooldown has elapsed
     * since the last electoral change. Demotes any prior electoral, promotes this one, stamps the change
     * instant, and audits {@code ELECTORAL_CHANGED(reason=MANUAL)}.
     *
     * @param userPublicId     the authenticated account.
     * @param locationPublicId the location to make electoral.
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if the location is not the caller's;
     *                      {@link ErrorCode#CONFLICT} if the electoral is voter-ID-authoritative (locked);
     *                      {@link ErrorCode#RATE_LIMITED} if the manual-change cooldown has not elapsed.
     */
    @Transactional
    public void setElectoralManual(UUID userPublicId, UUID locationPublicId) {
        Profile profile = requireProfile(userPublicId);
        ProfileLocation target = requireOwnedLocation(profile, locationPublicId);

        if (isElectoralAuthoritative(profile)) {
            // Voter-ID wins for electoral; a manual move is refused until re-verification (§25.4).
            auditElectoralDenied(userPublicId, "AUTHORITATIVE_LOCKED");
            throw new ApiException(ErrorCode.CONFLICT);
        }

        var currentOpt = profileLocationRepository.findByProfileAndElectoralTrue(profile);
        if (currentOpt.isPresent()) {
            ProfileLocation current = currentOpt.get();
            if (current.getPublicId().equals(target.getPublicId())) {
                return; // Already the electoral — idempotent no-op (no cooldown consumed).
            }
            Instant changedAt = current.getElectoralChangedAt();
            if (changedAt != null && clock.now().isBefore(changedAt.plus(electoralCooldown))) {
                // Inside the cooldown window: refuse the manual change (D13).
                auditElectoralDenied(userPublicId, "COOLDOWN");
                throw new ApiException(ErrorCode.RATE_LIMITED);
            }
            current.clearElectoral();
        }
        target.markElectoral(clock.now());
        auditElectoral(userPublicId, "MANUAL");
    }

    /**
     * <b>Voter-ID-authoritative</b> set of the single electoral location (from the approval flow, Flow 3),
     * for a resolved ward. Bypasses the cooldown and overrides any prior electoral (D13). Ensures a
     * {@link ProfileLocation} exists for the resolved ward (auto-creates an electoral-only pin if absent,
     * §25.4), demotes any prior electoral, and stamps the change. Audited
     * {@code ELECTORAL_CHANGED(reason=VOTER_ID_AUTHORITATIVE)} with the subject = the citizen.
     *
     * @param profile      the citizen's profile being verified.
     * @param wardPublicId the registered ward resolved from the voter ID.
     */
    @Transactional
    public void setElectoralAuthoritative(Profile profile, UUID wardPublicId) {
        GeographyQueryService.WardPin pin = geographyQueryService.resolveWardPin(wardPublicId);

        // Find an existing pin for this ward, else auto-create an electoral-only one (§25.4).
        ProfileLocation target = profileLocationRepository.findByProfile(profile).stream()
                .filter(pl -> pl.getWard().getPublicId().equals(wardPublicId))
                .findFirst()
                .orElseGet(() -> profileLocationRepository.save(ProfileLocation.pin(
                        profile, pin.ward(), pin.constituency(), AssociationType.INTEREST, false)));

        profileLocationRepository.findByProfileAndElectoralTrue(profile)
                .filter(existing -> !existing.getPublicId().equals(target.getPublicId()))
                .ifPresent(ProfileLocation::clearElectoral);
        target.markElectoral(clock.now());

        UUID citizenPublicId = profile.getUser().getPublicId();
        audit.record(AuditEvent.Builder
                .of(AuditEventType.ELECTORAL_CHANGED, AuditOutcome.SUCCESS)
                .actor(citizenPublicId).subject(citizenPublicId)
                .reason("VOTER_ID_AUTHORITATIVE").build());
    }

    /**
     * @param profile the profile.
     * @return whether the electoral location is locked by a verified voter ID (authoritative source, D13).
     *         WHY derived (not a stored flag): no new column is added this increment (§10.2); the voter-ID
     *         being the authority is exactly {@code idType == VOTER ∧ idVerified}.
     */
    private boolean isElectoralAuthoritative(Profile profile) {
        return profile.getIdType() == IdType.VOTER && profile.isIdVerified();
    }

    private void auditElectoral(UUID actor, String reason) {
        audit.record(AuditEvent.Builder
                .of(AuditEventType.ELECTORAL_CHANGED, AuditOutcome.SUCCESS)
                .actor(actor).subject(actor).reason(reason).build());
    }

    private void auditElectoralDenied(UUID actor, String reason) {
        audit.record(AuditEvent.Builder
                .of(AuditEventType.ELECTORAL_CHANGED, AuditOutcome.DENIED)
                .actor(actor).subject(actor).reason(reason).build());
    }

    private Profile requireProfile(UUID userPublicId) {
        User user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return profileRepository.findByUser(user)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private ProfileLocation requireOwnedLocation(Profile profile, UUID locationPublicId) {
        return profileLocationRepository.findByProfileAndPublicId(profile, locationPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
