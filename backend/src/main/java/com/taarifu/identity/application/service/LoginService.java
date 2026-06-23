package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.CryptoPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.AuthRateLimiter;
import com.taarifu.common.security.JwtService;
import com.taarifu.common.security.JwtVerificationException;
import com.taarifu.common.security.TokenType;
import com.taarifu.identity.domain.model.OtpChallenge;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.OtpPurpose;
import com.taarifu.identity.domain.model.enums.UserStatus;
import com.taarifu.identity.domain.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
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
    private final MfaLoginGate mfaLoginGate;
    private final TotpService totpService;
    private final JwtService jwtService;
    private final long mfaChallengeTtlSeconds;

    /**
     * @param userRepository  account lookup by phone/email.
     * @param otpService      OTP issue/verify for passwordless login.
     * @param tokenService    issues the token pair on success.
     * @param passwordEncoder BCrypt matcher (never compares plaintext).
     * @param rateLimiter     login lockout/backoff (S-2).
     * @param crypto          hashes the account key for rate-limit keys/audit refs (no raw PII).
     * @param audit           append-only audit writer.
     * @param mfaLoginGate    decides whether a login must complete a staff TOTP second factor (N-4).
     * @param totpService     verifies the TOTP code at the second-factor step.
     * @param jwtService      issues/verifies the short-lived {@code MFA_CHALLENGE} token (N-4).
     * @param mfaChallengeTtlSeconds the {@code MFA_CHALLENGE} TTL (config; ~5 min).
     */
    public LoginService(UserRepository userRepository,
                        OtpService otpService,
                        TokenService tokenService,
                        PasswordEncoder passwordEncoder,
                        AuthRateLimiter rateLimiter,
                        CryptoPort crypto,
                        AuditEventService audit,
                        MfaLoginGate mfaLoginGate,
                        TotpService totpService,
                        JwtService jwtService,
                        @Value("${taarifu.security.mfa.challenge.ttl-seconds:300}") long mfaChallengeTtlSeconds) {
        this.userRepository = userRepository;
        this.otpService = otpService;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.crypto = crypto;
        this.audit = audit;
        this.mfaLoginGate = mfaLoginGate;
        this.totpService = totpService;
        this.jwtService = jwtService;
        this.mfaChallengeTtlSeconds = mfaChallengeTtlSeconds;
    }

    /**
     * Password login by phone or email.
     *
     * @param accountKey the phone (E.164) or email identifying the account.
     * @param password   the presented password (checked against the BCrypt hash; never logged).
     * @return either the issued token pair, or — for a staff/MFA account — an MFA challenge requiring the
     *         TOTP second factor (N-4). A staff account can <b>never</b> complete login here directly.
     * @throws ApiException {@link ErrorCode#RATE_LIMITED} if locked/backing off;
     *                      {@link ErrorCode#UNAUTHENTICATED} on any credential/status failure (uniform).
     */
    @Transactional
    public LoginOutcome loginWithPassword(String accountKey, String password) {
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

        return firstFactorPassed(user, accountHash);
    }

    /**
     * Passwordless / recovery OTP login: verifies a LOGIN-purpose OTP for an existing active account.
     *
     * @param challengeId the LOGIN challenge id.
     * @param code        the OTP entered.
     * @return either the issued token pair, or an MFA challenge for a staff/MFA account (N-4).
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} on a bad/expired OTP;
     *                      {@link ErrorCode#UNAUTHENTICATED} if the verified phone has no active account.
     */
    @Transactional
    public LoginOutcome loginWithOtp(UUID challengeId, String code) {
        OtpChallenge challenge = otpService.verify(challengeId, code, OtpPurpose.LOGIN);
        User user = userRepository.findByPhone(challenge.getPhone())
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> {
                    audit.record(AuditEvent.Builder
                            .of(AuditEventType.AUTH_LOGIN_FAILED, AuditOutcome.FAILURE)
                            .reason("NO_ACTIVE_ACCOUNT").build());
                    return new ApiException(ErrorCode.UNAUTHENTICATED);
                });
        return firstFactorPassed(user, crypto.blindIndex(challenge.getPhone()));
    }

    /**
     * Completes the staff second factor (N-4, VERIFICATION-DESIGN §7.1): verifies the {@code MFA_CHALLENGE}
     * token issued by the first factor, checks the TOTP code, and only then issues the real token pair.
     *
     * <p>The second factor is <b>single-use</b> (review V-2, P2): {@link TotpService#verify} enforces TOTP
     * step monotonicity, so replaying a captured {@code (mfaToken, totp)} pair within the step window is
     * rejected ({@link TotpService.Result#REPLAYED}) and audited as
     * {@code AUTH_MFA_CHALLENGE_FAILED/DENIED/REPLAY} — it can no longer mint extra token families. The
     * watermark advance persists in this method's transaction, so it commits with the login it authorises.</p>
     *
     * @param mfaToken the {@code MFA_CHALLENGE} JWT returned by {@code loginWith*} for a staff/MFA account.
     * @param totpCode the current TOTP code from the authenticator.
     * @return the issued token pair.
     * @throws ApiException {@link ErrorCode#UNAUTHENTICATED} if the challenge token is invalid/expired or
     *                      the account is missing/inactive; {@link ErrorCode#BAD_REQUEST} on a wrong code
     *                      <b>or a replayed code</b> (same generic error — no oracle to an attacker).
     */
    @Transactional
    public TokenService.TokenPair completeTotpLogin(String mfaToken, String totpCode) {
        UUID subject;
        try {
            subject = jwtService.verify(mfaToken, TokenType.MFA_CHALLENGE).subject();
        } catch (JwtVerificationException e) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_MFA_CHALLENGE_FAILED, AuditOutcome.FAILURE)
                    .reason("INVALID_MFA_TOKEN").build());
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }

        // Rate-limit the TOTP step the same way as login (S-2): bound brute force of the 6-digit code.
        String accountHash = crypto.blindIndex(subject.toString());
        if (!rateLimiter.allowLoginAttempt(accountHash)) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_LOGIN_LOCKED, AuditOutcome.DENIED)
                    .actor(subject).reason("MFA_LOCKED").build());
            throw new ApiException(ErrorCode.RATE_LIMITED);
        }

        User user = userRepository.findByPublicId(subject)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));

        TotpService.Result result = totpService.verify(user, totpCode);
        if (result != TotpService.Result.ACCEPTED) {
            rateLimiter.recordLoginFailure(accountHash);
            // Distinguish a REPLAY of a captured (mfaToken, totp) pair from a plain wrong code (review V-2):
            // step monotonicity in TotpService makes the second redemption of the SAME code REPLAYED. Both
            // return the same generic error to the client (no oracle), but the replay is audited distinctly.
            String reason = result == TotpService.Result.REPLAYED ? "REPLAY" : "WRONG_TOTP";
            AuditOutcome outcome = result == TotpService.Result.REPLAYED
                    ? AuditOutcome.DENIED : AuditOutcome.FAILURE;
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_MFA_CHALLENGE_FAILED, outcome)
                    .actor(subject).subject(subject).reason(reason).build());
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }

        rateLimiter.resetLogin(accountHash);
        user.recordLogin(Instant.now());
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_LOGIN_MFA, AuditOutcome.SUCCESS)
                .actor(subject).subject(subject).reason("TOTP").build());
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_LOGIN_SUCCEEDED, AuditOutcome.SUCCESS)
                .actor(subject).subject(subject).build());
        return tokenService.issuePair(user);
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

    /**
     * Branch after the first factor passes: a staff/MFA account gets an {@code MFA_CHALLENGE} (N-4 — it
     * cannot complete login here); everyone else gets the real token pair. WHY login is <b>not</b>
     * recorded as succeeded for the MFA branch: login is incomplete until the TOTP step (§7.1) — the
     * success/last-login audit fires in {@link #completeTotpLogin}.
     */
    private LoginOutcome firstFactorPassed(User user, String accountHash) {
        if (mfaLoginGate.requiresSecondFactor(user)) {
            // First factor succeeded; reset its lockout but issue ONLY a short-lived MFA challenge token.
            rateLimiter.resetLogin(accountHash);
            if (!user.isMfaEnabled()) {
                // A staff account that never enrolled TOTP cannot complete a staff login (N-4, §7.2):
                // direct it to enrol. It may still use citizen features via a separate (non-staff) path,
                // but no token bearing staff authority is minted here.
                audit.record(AuditEvent.Builder
                        .of(AuditEventType.AUTH_MFA_CHALLENGE_FAILED, AuditOutcome.DENIED)
                        .actor(user.getPublicId()).subject(user.getPublicId())
                        .reason("STAFF_MFA_NOT_ENROLLED").build());
                throw new ApiException(ErrorCode.MFA_REQUIRED);
            }
            String mfaToken = jwtService.issueMfaChallengeToken(user.getPublicId(), mfaChallengeTtlSeconds);
            return LoginOutcome.mfaRequired(mfaToken);
        }
        return LoginOutcome.tokens(succeed(user, accountHash));
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

    /**
     * The result of a first-factor login: either a full token pair (no MFA needed) or an MFA challenge
     * requiring the staff TOTP second factor (N-4). Exactly one of the two is present.
     *
     * @param tokens   the issued access + refresh pair, or {@code null} when MFA is required.
     * @param mfaToken the short-lived {@code MFA_CHALLENGE} token, or {@code null} when tokens are issued.
     */
    public record LoginOutcome(TokenService.TokenPair tokens, String mfaToken) {

        /** @return {@code true} if the staff TOTP second factor must be completed before access is granted. */
        public boolean mfaRequired() {
            return mfaToken != null;
        }

        /** @param pair the issued token pair. @return a no-MFA outcome carrying the pair. */
        static LoginOutcome tokens(TokenService.TokenPair pair) {
            return new LoginOutcome(pair, null);
        }

        /** @param mfaToken the challenge token. @return an MFA-required outcome carrying the challenge. */
        static LoginOutcome mfaRequired(String mfaToken) {
            return new LoginOutcome(null, mfaToken);
        }
    }
}
