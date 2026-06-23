package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.repository.UserRepository;
import com.taarifu.identity.infrastructure.totp.Base32;
import com.taarifu.identity.infrastructure.totp.TotpGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * TOTP (RFC 6238) staff second-factor provisioning + verification (N-4, VERIFICATION-DESIGN §2.3, §7).
 *
 * <p>Responsibility: the staff-MFA secret lifecycle. {@link #setup(UUID)} mints a fresh Base32 secret,
 * stores it <b>encrypted</b> in the account's {@code mfa_pending_secret} slot (the entity converter does
 * the encryption), and returns the {@code otpauth://} provisioning URI + the raw secret <b>once</b> for
 * manual entry. {@link #activate(UUID, String)} verifies a code against the pending secret, promotes it
 * to the active secret, sets {@code mfaEnabled=true}, and audits {@code AUTH_MFA_ENROLLED}.
 * {@link #verify(User, String)} checks a presented code against the <b>active</b> secret with a ±1-step
 * window for the login second factor.</p>
 *
 * <p>WHY two secret slots (pending vs active): an un-activated secret can never satisfy a login — only a
 * code proven by {@code activate} promotes the secret, so a half-finished enrolment cannot weaken the
 * gate (§2.3). The raw secret is surfaced only at {@code setup}; it is never logged and never returned
 * again (S-4).</p>
 */
@Service
public class TotpService {

    /** Entropy of a generated secret (20 bytes = 160-bit, the authenticator default). */
    private static final int SECRET_BYTES = 20;
    private static final String ISSUER = "Taarifu";

    private final UserRepository userRepository;
    private final AuditEventService audit;
    private final ClockPort clock;
    private final TotpGenerator totp;

    /**
     * @param userRepository account persistence (the secret columns live on {@code app_user}).
     * @param audit          append-only audit writer (enrolment + failure events).
     * @param clock          time source for the TOTP window (testable — never {@code Instant.now()} inline).
     * @param stepSeconds    the TOTP step in seconds (config {@code taarifu.security.mfa.totp.step-seconds}).
     */
    public TotpService(UserRepository userRepository,
                       AuditEventService audit,
                       ClockPort clock,
                       @Value("${taarifu.security.mfa.totp.step-seconds:30}") int stepSeconds) {
        this.userRepository = userRepository;
        this.audit = audit;
        this.clock = clock;
        this.totp = new TotpGenerator(stepSeconds);
    }

    /**
     * Provisions a fresh pending TOTP secret for an account and returns the enrolment material once.
     *
     * @param userPublicId the authenticated account enrolling MFA.
     * @return the {@code otpauth} URI + raw secret (shown once; never stored unencrypted, never logged).
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if the account is missing.
     */
    @Transactional
    public TotpEnrolment setup(UUID userPublicId) {
        User user = requireUser(userPublicId);
        String secret = Base32.randomSecret(SECRET_BYTES);
        user.setMfaPendingSecret(secret);
        String uri = "otpauth://totp/" + ISSUER + ":" + user.getPublicId()
                + "?secret=" + secret + "&issuer=" + ISSUER + "&algorithm=SHA1&digits=6&period=30";
        return new TotpEnrolment(uri, secret);
    }

    /**
     * Activates MFA: verifies a code against the pending secret, promotes it to active, sets
     * {@code mfaEnabled=true}.
     *
     * @param userPublicId the authenticated account.
     * @param code         the current TOTP code from the authenticator.
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if the account is missing;
     *                      {@link ErrorCode#BAD_REQUEST} if no setup was done or the code is wrong.
     */
    @Transactional
    public void activate(UUID userPublicId, String code) {
        User user = requireUser(userPublicId);
        String pending = user.getMfaPendingSecret();
        if (pending == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
        if (!totp.verify(pending, code, epochSeconds())) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_MFA_CHALLENGE_FAILED, AuditOutcome.FAILURE)
                    .actor(userPublicId).subject(userPublicId).reason("ACTIVATE_WRONG_CODE").build());
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
        user.enableMfa();
        // WHY we do NOT advance last_totp_step here (review V-2): the replay watermark guards the LOGIN
        // second factor, and a citizen who has just enrolled may legitimately complete their first login
        // in the SAME time-step with the same code. Advancing on activate would make that first login look
        // like a replay (step <= last) and wrongly fail. Activation already requires an authenticated
        // session, so it is not the captured-pair threat V-2 addresses; the watermark advances only on a
        // successful login TOTP step (see verify()).
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_MFA_ENROLLED, AuditOutcome.SUCCESS)
                .actor(userPublicId).subject(userPublicId).build());
    }

    /**
     * Verifies a presented TOTP code against an account's <b>active</b> secret (the login second factor),
     * with a ±1-step window (§2.3), and enforces <b>step monotonicity</b> so the factor is <b>single-use</b>
     * (review V-2, N-4).
     *
     * <p>WHY this is no longer a pure read (it advances {@code last_totp_step}): a captured
     * {@code (mfaToken, totp)} pair was replayable within the step window to mint extra token families. By
     * recording the highest accepted step and refusing any code whose step {@code <=} it, the first
     * redemption consumes the code and a replay of the identical pair returns {@link Result#REPLAYED} —
     * letting the caller reject and audit it distinctly from a wrong code. The advance is persisted in the
     * same transaction as the login it authorises.</p>
     *
     * @param user the staff account completing the second factor.
     * @param code the TOTP code entered.
     * @return {@link Result#ACCEPTED} on a fresh valid code (watermark advanced);
     *         {@link Result#REPLAYED} when the code is valid but its step was already consumed (replay);
     *         {@link Result#REJECTED} when MFA is not active or the code does not match the window.
     */
    @Transactional
    public Result verify(User user, String code) {
        String secret = user.getMfaTotpSecret();
        if (secret == null) {
            return Result.REJECTED;
        }
        long step = totp.matchedStep(secret, code, epochSeconds());
        if (step == TotpGenerator.NO_MATCH) {
            return Result.REJECTED;
        }
        if (step <= user.getLastTotpStep()) {
            // Valid code, but its step was already accepted — a replay of a captured pair (review V-2).
            return Result.REPLAYED;
        }
        user.advanceLastTotpStep(step);
        return Result.ACCEPTED;
    }

    /**
     * The outcome of a login-time TOTP verification (review V-2). Distinguishing {@link #REPLAYED} from
     * {@link #REJECTED} lets {@code LoginService} audit a second-factor replay attempt with its own reason
     * code while returning the same generic error to the client (no oracle to an attacker).
     */
    public enum Result {
        /** Fresh valid code; the replay watermark was advanced. */
        ACCEPTED,
        /** Valid code whose time-step was already consumed — a replay (review V-2). */
        REPLAYED,
        /** No active secret, or the code does not match the ±1-step window. */
        REJECTED
    }

    private long epochSeconds() {
        return clock.now().getEpochSecond();
    }

    private User requireUser(UUID userPublicId) {
        return userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /**
     * The one-time enrolment material returned by {@link #setup(UUID)}.
     *
     * @param otpauthUri the {@code otpauth://totp/...} provisioning URI (scan into an authenticator).
     * @param secret     the raw Base32 secret for manual entry — shown once, never logged, never re-fetched.
     */
    public record TotpEnrolment(String otpauthUri, String secret) {
    }
}
