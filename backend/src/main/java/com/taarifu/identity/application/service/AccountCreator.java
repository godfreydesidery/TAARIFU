package com.taarifu.identity.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.Role;
import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.RoleName;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import com.taarifu.identity.domain.repository.RoleRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import org.springframework.stereotype.Service;

/**
 * The single source of the "create one new <b>T1</b> account for a phone" routine (D11/D15) — shared by the
 * OTP {@code SignupService} and the USSD {@code AccountProvisioningService} so the one-account-per-phone
 * creation logic exists in exactly one place (DRY; CLAUDE.md §3 — the legacy code's biggest sin was
 * copy-paste).
 *
 * <p>Responsibility: persist a new {@link User} (ACTIVE), its phone-verified person {@link Profile}, and the
 * base {@link RoleName#CITIZEN} grant, then set the server-computed live tier (T1 at this point) — the exact
 * account-creation block both entry paths need. It does <b>not</b> issue tokens (the OTP path issues a token
 * pair itself; the USSD path needs no JWT) and does <b>not</b> guard one-account-per-phone — the <b>caller</b>
 * checks {@code userRepository.findByPhone} first and decides the duplicate policy (signup rejects with
 * {@code CONFLICT}; provisioning returns the existing account idempotently). Keeping the duplicate decision at
 * the call site lets each channel apply its own contract while the create core stays identical.</p>
 *
 * <p>WHY package-private and method-on-a-service (not a static helper): it needs the identity repositories,
 * so it is a Spring bean injected into both services; the {@code @Transactional} boundary stays on the
 * <b>calling</b> service so the whole signup/provisioning step is one atomic unit.</p>
 */
@Service
public class AccountCreator {

    private final TierService tierService;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final RoleRepository roleRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;

    /**
     * @param tierService              recomputes + caches the live tier (T1 for a phone-verified account).
     * @param userRepository           account persistence.
     * @param profileRepository        profile persistence.
     * @param roleRepository           CITIZEN catalogue row lookup.
     * @param roleAssignmentRepository grants the CITIZEN role.
     */
    public AccountCreator(TierService tierService,
                          UserRepository userRepository,
                          ProfileRepository profileRepository,
                          RoleRepository roleRepository,
                          RoleAssignmentRepository roleAssignmentRepository) {
        this.tierService = tierService;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.roleRepository = roleRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
    }

    /**
     * Creates and persists a new ACTIVE account at T1 for {@code phone}: the {@link User}, its phone-verified
     * person {@link Profile}, the base {@code CITIZEN} grant, and the server-computed tier. Must be called
     * inside the caller's transaction, and only after the caller has confirmed no account exists for the
     * phone (the one-account-per-phone guard is the caller's, D11/D15).
     *
     * @param phone the unique E.164 phone (the one-account-per-phone key).
     * @return the persisted {@link User} (its {@code publicId} is the account handle); the computed tier is
     *         cached on it via {@link User#getTrustTier()}.
     * @throws ApiException {@link ErrorCode#INTERNAL_ERROR} if the {@code CITIZEN} catalogue row is missing
     *                      (a seed/data defect, never a client error).
     */
    public User createCitizenAtT1(String phone) {
        User user = User.createPending(phone);
        user.activate();
        user = userRepository.save(user);

        Profile profile = Profile.createPersonForSignup(user);
        profileRepository.save(profile);

        Role citizen = roleRepository.findByName(RoleName.CITIZEN)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR));
        roleAssignmentRepository.save(RoleAssignment.grant(user, citizen, RoleStatus.ACTIVE));

        // Server computes the tier (T1 here for a phone-verified profile) and caches it; the client/caller
        // never asserts a tier (MF-2).
        TrustTier tier = tierService.resolveLiveTier(profile);
        user.setTrustTier(tier);
        return user;
    }
}
