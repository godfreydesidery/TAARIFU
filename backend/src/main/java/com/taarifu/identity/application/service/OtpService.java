package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.domain.port.CryptoPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.AuthRateLimiter;
import com.taarifu.communications.domain.port.SmsGateway;
import com.taarifu.identity.domain.model.OtpChallenge;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.OtpChannel;
import com.taarifu.identity.domain.model.enums.OtpPurpose;
import com.taarifu.identity.domain.repository.OtpChallengeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

/**
 * Issues and verifies single-use, short-TTL OTP challenges (AUTH-DESIGN §3, §9, ADR-0011 §6).
 *
 * <p>Responsibility: the OTP half of signup/login/channel-verify. On issue it enforces the per-recipient
 * send-rate ({@link AuthRateLimiter}), generates a 6-digit code, stores only its <b>keyed hash</b>
 * ({@link CryptoPort#blindIndex} — never the plaintext, S-4), persists the challenge with a ~5-minute
 * TTL and a 5-attempt cap, and delivers via the {@link SmsGateway} port. On verify it enforces the
 * attempt cap, single-use, and expiry, then consumes the challenge. The code value is <b>never logged</b>
 * and the {@code 202}-style responses never reveal whether a phone exists (anti-enumeration).</p>
 *
 * <p>WHY the hash, not the code: a DB or log compromise must yield no usable OTP (PRD §18). The same
 * deterministic keyed-HMAC primitive used for the ID blind index hashes the code, so verify is an
 * equality compare on the hash with no plaintext at rest.</p>
 */
@Service
public class OtpService {

    private static final int CODE_DIGITS = 6;
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpChallengeRepository challengeRepository;
    private final CryptoPort crypto;
    private final AuthRateLimiter rateLimiter;
    private final SmsGateway smsGateway;
    private final AuditEventService audit;
    private final ClockPort clock;
    private final Duration ttl;

    /**
     * @param challengeRepository challenge persistence.
     * @param crypto              hashes the code + the recipient (for rate-limit keys/audit refs).
     * @param rateLimiter         OTP send/attempt anti-automation (S-2).
     * @param smsGateway          delivery port (dev stub in dev/test).
     * @param audit               append-only audit writer.
     * @param clock               time source (testable TTL/expiry).
     * @param ttlSeconds          OTP lifetime in seconds (default 300 = 5 min, config-tunable).
     */
    public OtpService(OtpChallengeRepository challengeRepository,
                      CryptoPort crypto,
                      AuthRateLimiter rateLimiter,
                      SmsGateway smsGateway,
                      AuditEventService audit,
                      ClockPort clock,
                      @Value("${taarifu.security.otp.ttl-seconds:300}") long ttlSeconds) {
        this.challengeRepository = challengeRepository;
        this.crypto = crypto;
        this.rateLimiter = rateLimiter;
        this.smsGateway = smsGateway;
        this.audit = audit;
        this.clock = clock;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * Issues an SMS OTP challenge for a phone.
     *
     * @param phone   the E.164 phone the code is sent to (never logged).
     * @param purpose the flow this code authorises.
     * @param user    the owning account, or {@code null} for signup-before-account.
     * @return the new challenge's public id (handed to the client to verify against).
     * @throws ApiException {@link ErrorCode#RATE_LIMITED} if the send-rate cap is exceeded.
     */
    @Transactional
    public UUID issueSms(String phone, OtpPurpose purpose, User user) {
        String recipientHash = crypto.blindIndex(phone);
        if (!rateLimiter.allowOtpSend(recipientHash)) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_OTP_REQUESTED, AuditOutcome.DENIED)
                    .reason("RATE_LIMITED").build());
            throw new ApiException(ErrorCode.RATE_LIMITED);
        }

        String code = generateCode();
        OtpChallenge challenge = OtpChallenge.create(
                phone, null, purpose, OtpChannel.SMS,
                crypto.blindIndex(code), clock.now().plus(ttl), MAX_ATTEMPTS, user);
        challengeRepository.save(challenge);

        // Deliver via the port. The body carries the code; it is never logged (the stub redacts).
        smsGateway.send(new SmsGateway.SmsMessage(
                phone,
                buildBody(code, purpose),
                purpose.name() + "_OTP",
                challenge.getPublicId().toString()));

        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_OTP_REQUESTED, AuditOutcome.SUCCESS)
                .reason(purpose.name())
                .detailRef("phone_hash:" + recipientHash)   // ref/hash only, never the raw phone (S-4)
                .build());
        return challenge.getPublicId();
    }

    /**
     * Verifies a presented code against a challenge (single-use, attempt-capped, TTL-bounded).
     *
     * @param challengeId the challenge public id.
     * @param code        the code the user entered.
     * @param expected    the purpose the calling flow expects (a SIGNUP code cannot complete a LOGIN).
     * @return the verified challenge (consumed) for the caller to act on (e.g. resolve/activate the user).
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} for an invalid/expired/burned challenge or a
     *                      wrong code (uniform message — never reveals which).
     */
    @Transactional
    public OtpChallenge verify(UUID challengeId, String code, OtpPurpose expected) {
        OtpChallenge challenge = challengeRepository.findByPublicId(challengeId).orElse(null);
        if (challenge == null || challenge.getPurpose() != expected
                || !challenge.isVerifiable(clock.now())) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_OTP_FAILED, AuditOutcome.FAILURE)
                    .reason("INVALID_OR_EXPIRED").build());
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
        if (!rateLimiter.allowOtpVerifyAttempt(challengeId.toString())) {
            challenge.registerFailedAttempt();
            throw new ApiException(ErrorCode.RATE_LIMITED);
        }
        if (!crypto.blindIndex(code).equals(challenge.getCodeHash())) {
            challenge.registerFailedAttempt();
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_OTP_FAILED, AuditOutcome.FAILURE)
                    .reason("WRONG_CODE").build());
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
        challenge.consume();
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_OTP_VERIFIED, AuditOutcome.SUCCESS)
                .reason(expected.name()).build());
        return challenge;
    }

    /** Generates a uniformly-random fixed-length numeric code. */
    private static String generateCode() {
        int bound = (int) Math.pow(10, CODE_DIGITS);
        int n = RANDOM.nextInt(bound);
        return String.format("%0" + CODE_DIGITS + "d", n);
    }

    /** Builds the (i18n-ready) SMS body. The code is the only sensitive content; never logged (S-4). */
    private static String buildBody(String code, OtpPurpose purpose) {
        // KISS: a literal Swahili-first template; the communications increment moves this to i18n bundles.
        return "Taarifu: msimbo wako ni " + code + " (" + purpose.name() + "). Usimshirikishe mtu yeyote.";
    }
}
