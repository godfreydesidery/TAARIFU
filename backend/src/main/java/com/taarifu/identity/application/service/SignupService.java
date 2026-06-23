package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.identity.domain.model.OtpChallenge;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.Role;
import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.OtpPurpose;
import com.taarifu.identity.domain.model.enums.RoleName;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import com.taarifu.identity.domain.repository.RoleRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Phone + OTP signup → an active account at trust-tier T1 (AUTH-DESIGN §3, ADR-0011 §1, D11/D15).
 *
 * <p>Responsibility: requests the SIGNUP OTP, and on verified OTP creates (or activates) <b>exactly one
 * account per phone</b>, a phone-verified {@link Profile}, and the base {@link RoleName#CITIZEN} grant,
 * then issues the first token pair — all in one transaction. An <b>existing</b> phone is never given a
 * second account (D11/D15): request returns the same non-committal response, and a verified existing
 * phone is treated as a no-op success (the client should log in instead). The {@code T1} tier is set by
 * server logic, never by client input (MF-2).</p>
 */
@Service
public class SignupService {

    private final OtpService otpService;
    private final TokenService tokenService;
    private final TierService tierService;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final RoleRepository roleRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final AuditEventService audit;

    /**
     * @param otpService               OTP issue/verify.
     * @param tokenService             issues the first token pair.
     * @param tierService              recomputes + caches the live tier.
     * @param userRepository           account persistence (one-per-phone guard).
     * @param profileRepository        profile persistence.
     * @param roleRepository           CITIZEN catalogue row lookup.
     * @param roleAssignmentRepository grants the CITIZEN role.
     * @param audit                    append-only audit writer.
     */
    public SignupService(OtpService otpService,
                         TokenService tokenService,
                         TierService tierService,
                         UserRepository userRepository,
                         ProfileRepository profileRepository,
                         RoleRepository roleRepository,
                         RoleAssignmentRepository roleAssignmentRepository,
                         AuditEventService audit) {
        this.otpService = otpService;
        this.tokenService = tokenService;
        this.tierService = tierService;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.roleRepository = roleRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.audit = audit;
    }

    /**
     * Requests a signup OTP for a phone. Returns the challenge id whether or not the phone already has an
     * account (anti-enumeration); an existing phone still receives a code, but verifying it will not mint
     * a second account (D11/D15).
     *
     * @param phone the E.164 phone.
     * @return the challenge public id to verify against.
     */
    @Transactional
    public UUID requestSignupOtp(String phone) {
        User existing = userRepository.findByPhone(phone).orElse(null);
        return otpService.issueSms(phone, OtpPurpose.SIGNUP, existing);
    }

    /**
     * Completes signup: verifies the OTP, then creates/activates the T1 account and issues tokens.
     *
     * @param challengeId the signup challenge id.
     * @param code        the OTP the user entered.
     * @return the new account's public id + tier + the issued token pair.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} on a bad/expired OTP;
     *                      {@link ErrorCode#CONFLICT} if the phone already has an active account
     *                      (the caller should log in, not sign up again — D11/D15).
     */
    @Transactional
    public SignupResult completeSignup(UUID challengeId, String code) {
        OtpChallenge challenge = otpService.verify(challengeId, code, OtpPurpose.SIGNUP);
        String phone = challenge.getPhone();

        User existing = userRepository.findByPhone(phone).orElse(null);
        if (existing != null) {
            // One account per phone (D11/D15): an existing active account must log in, not re-sign up.
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_SIGNUP_COMPLETED, AuditOutcome.DENIED)
                    .actor(existing.getPublicId()).reason("PHONE_EXISTS").build());
            throw new ApiException(ErrorCode.CONFLICT);
        }

        User user = User.createPending(phone);
        user.activate();
        user = userRepository.save(user);

        Profile profile = Profile.createPersonForSignup(user);
        profileRepository.save(profile);

        Role citizen = roleRepository.findByName(RoleName.CITIZEN)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR));
        roleAssignmentRepository.save(RoleAssignment.grant(user, citizen, RoleStatus.ACTIVE));

        // Server computes the tier (T1 here) and caches it; the client never asserts a tier (MF-2).
        TrustTier tier = tierService.resolveLiveTier(profile);
        user.setTrustTier(tier);

        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_SIGNUP_COMPLETED, AuditOutcome.SUCCESS)
                .actor(user.getPublicId()).subject(user.getPublicId()).build());
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_TIER_CHANGED, AuditOutcome.SUCCESS)
                .actor(user.getPublicId()).subject(user.getPublicId())
                .reason("T0->" + tier.name()).build());

        TokenService.TokenPair tokens = tokenService.issuePair(user);
        return new SignupResult(user.getPublicId(), tier, tokens);
    }

    /**
     * The outcome of a completed signup.
     *
     * @param userPublicId the new account's public id.
     * @param tier         the server-computed trust tier (T1 at signup).
     * @param tokens       the issued access + refresh pair.
     */
    public record SignupResult(UUID userPublicId, TrustTier tier, TokenService.TokenPair tokens) {
    }
}
