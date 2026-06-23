package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.CryptoPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.AuthRateLimiter;
import com.taarifu.identity.domain.model.OtpChallenge;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.OtpPurpose;
import com.taarifu.identity.domain.model.enums.UserStatus;
import com.taarifu.identity.domain.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Password and OTP login onto one token issuance, with lockout/backoff (AUTH-DESIGN §4, §9, ADR-0011 §6).
 *
 * <p>Responsibility: authenticates an existing account by password or by OTP and issues the token pair.
 * It is hardened against credential-stuffing and enumeration (S-2, PRD §18):</p>
 * <ul>
 *   <li><b>Lockout/backoff</b>: a pre-check ({@link AuthRateLimiter#allowLoginAttempt}) blocks while the
 *       account is locked/backing off; failures advance the state; success resets it.</li>
 *   <li><b>No enumeration</b>: wrong password, unknown account, and disabled account all yield the same
 *       generic {@link ErrorCode#UNAUTHENTICATED}; the precise reason is only in the audit trail.</li>
 *   <li><b>Constant-ish work</b>: a password check runs against a dummy hash when the account/credential
 *       is absent, so timing does not betray account existence.</li>
 * </ul>
 */
@Service
public class LoginService {

    /**
     * A fixed, valid BCrypt hash of a value no caller knows, compared against when no real hash exists,
     * so a password check runs in constant-ish time whether or not the account/credential exists
     * (anti-timing / anti-enumeration — S-2, PRD §18). It can never match a user-supplied password.
     */
    private static final String DUMMY_BCRYPT =
            "$2a$10$zfC2DnJ/ymSwnHunHTd2wuHIvq2N9G2LRJWSXCs4hC9endBRbTMBW";

    private final UserRepository userRepository;
    private final OtpService otpService;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimiter rateLimiter;
    private final CryptoPort crypto;
    private final AuditEventService audit;

    /**
     * @param userRepository  account lookup by phone/email.
     * @param otpService      OTP issue/verify for passwordless login.
     * @param tokenService    issues the token pair on success.
     * @param passwordEncoder BCrypt matcher (never compares plaintext).
     * @param rateLimiter     login lockout/backoff (S-2).
     * @param crypto          hashes the account key for rate-limit keys/audit refs (no raw PII).
     * @param audit           append-only audit writer.
     */
    public LoginService(UserRepository userRepository,
                        OtpService otpService,
                        TokenService tokenService,
                        PasswordEncoder passwordEncoder,
                        AuthRateLimiter rateLimiter,
                        CryptoPort crypto,
                        AuditEventService audit) {
        this.userRepository = userRepository;
        this.otpService = otpService;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.crypto = crypto;
        this.audit = audit;
    }

    /**
     * Password login by phone or email.
     *
     * @param accountKey the phone (E.164) or email identifying the account.
     * @param password   the presented password (checked against the BCrypt hash; never logged).
     * @return the issued token pair on success.
     * @throws ApiException {@link ErrorCode#RATE_LIMITED} if locked/backing off;
     *                      {@link ErrorCode#UNAUTHENTICATED} on any credential/status failure (uniform).
     */
    @Transactional
    public TokenService.TokenPair loginWithPassword(String accountKey, String password) {
        String accountHash = crypto.blindIndex(accountKey);
        if (!rateLimiter.allowLoginAttempt(accountHash)) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_LOGIN_LOCKED, AuditOutcome.DENIED)
                    .reason("LOCKED").build());
            throw new ApiException(ErrorCode.RATE_LIMITED);
        }

        Optional<User> userOpt = resolveAccount(accountKey);
        // Always run a BCrypt compare (real or dummy) so timing does not reveal account existence.
        String hash = userOpt.map(User::getPasswordHash).orElse(DUMMY_BCRYPT);
        boolean passwordOk = hash != null && passwordEncoder.matches(password, hash);

        User user = userOpt.orElse(null);
        if (user == null || !passwordOk || user.getStatus() != UserStatus.ACTIVE) {
            rateLimiter.recordLoginFailure(accountHash);
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_LOGIN_FAILED, AuditOutcome.FAILURE)
                    .actor(user == null ? null : user.getPublicId())
                    .reason(reasonFor(user, passwordOk)).build());
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }

        return succeed(user, accountHash);
    }

    /**
     * Passwordless / recovery OTP login: verifies a LOGIN-purpose OTP for an existing active account.
     *
     * @param challengeId the LOGIN challenge id.
     * @param code        the OTP entered.
     * @return the issued token pair on success.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} on a bad/expired OTP;
     *                      {@link ErrorCode#UNAUTHENTICATED} if the verified phone has no active account.
     */
    @Transactional
    public TokenService.TokenPair loginWithOtp(UUID challengeId, String code) {
        OtpChallenge challenge = otpService.verify(challengeId, code, OtpPurpose.LOGIN);
        User user = userRepository.findByPhone(challenge.getPhone())
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> {
                    audit.record(AuditEvent.Builder
                            .of(AuditEventType.AUTH_LOGIN_FAILED, AuditOutcome.FAILURE)
                            .reason("NO_ACTIVE_ACCOUNT").build());
                    return new ApiException(ErrorCode.UNAUTHENTICATED);
                });
        return succeed(user, crypto.blindIndex(challenge.getPhone()));
    }

    /**
     * Requests a LOGIN OTP for a phone (non-committal — returns the challenge id regardless of whether
     * the phone maps to an account, anti-enumeration).
     *
     * @param phone the E.164 phone.
     * @return the challenge public id.
     */
    @Transactional
    public UUID requestLoginOtp(String phone) {
        User user = userRepository.findByPhone(phone).orElse(null);
        return otpService.issueSms(phone, OtpPurpose.LOGIN, user);
    }

    /** Records the success, resets lockout, stamps last-login, issues tokens. */
    private TokenService.TokenPair succeed(User user, String accountHash) {
        rateLimiter.resetLogin(accountHash);
        user.recordLogin(Instant.now());
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_LOGIN_SUCCEEDED, AuditOutcome.SUCCESS)
                .actor(user.getPublicId()).subject(user.getPublicId()).build());
        return tokenService.issuePair(user);
    }

    /** Resolves an account by phone (E.164 starts with '+') or otherwise by email. */
    private Optional<User> resolveAccount(String accountKey) {
        if (accountKey != null && accountKey.startsWith("+")) {
            return userRepository.findByPhone(accountKey);
        }
        return userRepository.findByEmail(accountKey);
    }

    /** Maps a failure to a precise (audit-only) reason code; never surfaced to the client. */
    private static String reasonFor(User user, boolean passwordOk) {
        if (user == null) {
            return "UNKNOWN";
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            return "STATUS_" + user.getStatus().name();
        }
        return passwordOk ? "OK" : "INVALID_CREDENTIALS";
    }
}
