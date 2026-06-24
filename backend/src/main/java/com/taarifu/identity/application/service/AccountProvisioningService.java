package com.taarifu.identity.application.service;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.identity.api.AccountProvisioningApi;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.ProfileLocation;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.repository.ProfileLocationRepository;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Identity's implementation of the published {@link AccountProvisioningApi} — the synchronous
 * {@code ussd → identity} "ensure an account by MSISDN" seam (ADR-0013 §1, §4d; PRD §14, EI-4, US-0.1).
 *
 * <p>Responsibility: idempotently resolve or create the T1 account for a phone, and read its registered
 * (primary) ward, so the feature-phone channel can file/track without OTP and without reaching into identity's
 * internals. Account creation is delegated to the shared {@link AccountCreator} (DRY with {@code SignupService}
 * — the one-account-per-phone create logic lives in exactly one place). This service owns only the
 * <b>idempotency decision</b> (known phone → existing account; unknown → create) and the read mapping.</p>
 *
 * <p><b>Integrity (D11/D15):</b> the one-account-per-phone invariant holds by construction — a known MSISDN
 * returns its existing account and the create path is reached only when {@code findByPhone} is empty, inside a
 * single transaction (the unique phone index is the hard DB backstop against a concurrent double-create).
 * <b>Fence (D18, §23.5):</b> no token/wallet collaborator is referenced — this is account provisioning only,
 * never a balance read. The raw MSISDN is never logged (S-4); the account public id is not PII.</p>
 */
@Service
public class AccountProvisioningService implements AccountProvisioningApi {

    private final AccountCreator accountCreator;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ProfileLocationRepository profileLocationRepository;
    private final AuditEventService audit;
    private final IdentityFunnelAnalytics funnel;

    /**
     * @param accountCreator            the shared create-T1-account routine (DRY with signup).
     * @param userRepository            account lookup (one-per-phone idempotency) + persistence.
     * @param profileRepository         resolves the account public id → its {@code Profile} (registered-ward read).
     * @param profileLocationRepository reads the profile's single primary location (registered ward, D12).
     * @param audit                     append-only audit writer (records first provisioning of a USSD account).
     * @param funnel                    emits the {@code account_signed_up} verification-funnel fact (A1; channel USSD).
     */
    public AccountProvisioningService(AccountCreator accountCreator,
                                      UserRepository userRepository,
                                      ProfileRepository profileRepository,
                                      ProfileLocationRepository profileLocationRepository,
                                      AuditEventService audit,
                                      IdentityFunnelAnalytics funnel) {
        this.accountCreator = accountCreator;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.profileLocationRepository = profileLocationRepository;
        this.audit = audit;
        this.funnel = funnel;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public UUID ensureAccountByMsisdn(String e164) {
        if (e164 == null || e164.isBlank()) {
            // A blank phone is a caller bug, not a "create" — reject cleanly rather than mint a bad account.
            throw new ApiException(ErrorCode.BAD_REQUEST, "identity.accountKey.required");
        }
        String phone = e164.trim();

        // Idempotent: a known MSISDN returns its existing account — never a second one (D11/D15).
        Optional<User> existing = userRepository.findByPhone(phone);
        if (existing.isPresent()) {
            return existing.get().getPublicId();
        }

        // Unknown phone: create a fresh ACTIVE T1 account via the shared create core (same invariants as
        // OTP signup, minus the OTP — the mobile network proved SIM ownership). The unique phone index is the
        // hard backstop if two USSD dialogues race the create.
        User user = accountCreator.createCitizenAtT1(phone);
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_SIGNUP_COMPLETED, AuditOutcome.SUCCESS)
                .actor(user.getPublicId()).subject(user.getPublicId())
                .reason("USSD_PROVISION").build());

        // ANALYTICS (A1, §3.3 funnel): the same T0→T1 funnel-entry fact as OTP signup, but tagged channel
        // USSD so the funnel/channel-mix dashboards see feature-phone onboarding. Emitted on the outbox in
        // THIS transaction, recorded asynchronously off the dialogue path. Coarse tier+channel only, no PII.
        funnel.emit(AnalyticsEventTypes.ACCOUNT_SIGNED_UP, user.getTrustTier().name(),
                IdentityFunnelAnalytics.CHANNEL_USSD);
        return user.getPublicId();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> registeredWardId(UUID accountPublicId) {
        if (accountPublicId == null) {
            return Optional.empty();
        }
        // Account → its profile → the single primary (default-context) location → its ward (D12).
        // Deny-by-default at every missing link: no profile, or no primary pin, yields empty.
        return profileRepository.findByUser_PublicId(accountPublicId)
                .flatMap(this::primaryWardOf);
    }

    /**
     * Resolves a profile's registered (primary) ward public id, or empty.
     *
     * @param profile the account's profile.
     * @return the primary location's ward public id, or empty if the profile has no primary pin.
     */
    private Optional<UUID> primaryWardOf(Profile profile) {
        return profileLocationRepository.findByProfileAndPrimaryTrue(profile)
                .map(ProfileLocation::getWard)
                .map(ward -> ward.getPublicId());
    }
}
