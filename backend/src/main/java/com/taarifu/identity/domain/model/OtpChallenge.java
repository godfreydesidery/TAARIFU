package com.taarifu.identity.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.identity.domain.model.enums.OtpChannel;
import com.taarifu.identity.domain.model.enums.OtpPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * A short-lived, single-use one-time-passcode challenge (AUTH-DESIGN §3, §13.1, ADR-0011 §10).
 *
 * <p>Responsibility: persists the server side of an OTP flow (signup / login / channel-verify) so a
 * challenge survives a process/Redis restart mid-flow and is auditable. The plaintext code is
 * <b>never</b> stored — only a keyed {@link #codeHash} (HMAC via {@code CryptoPort.blindIndex}) — so a
 * DB read yields no usable code (S-4, PRD §18). The fast anti-automation counters (send-rate, per-IP)
 * live in the {@code AuthRateLimiter}, not here; this row owns the per-challenge verify-attempt cap,
 * the TTL, and single-use consumption.</p>
 *
 * <p>WHY the target ({@link #phone}/{@link #email}) is stored in clear here: it is transient (TTL ~5
 * min), already present on {@link User} for existing accounts, and the gateway needs it to deliver the
 * code. It is <b>never logged</b> (S-4). For signup-before-account the {@link #user} FK is {@code null}.</p>
 *
 * <p>WHY no Lombok {@code @ToString}: this row references a delivery target (PII-adjacent); an
 * accidental {@code log.debug("{}", challenge)} must not leak it (S-4). Accessors are explicit.</p>
 */
@Entity
@Table(name = "otp_challenge", indexes = {
        @Index(name = "ix_otp_challenge_phone", columnList = "phone"),
        @Index(name = "ix_otp_challenge_expires", columnList = "expires_at"),
        @Index(name = "ix_otp_challenge_user", columnList = "user_id")
})
@SQLRestriction("deleted = false")
public class OtpChallenge extends BaseEntity {

    /** Target phone in E.164, or {@code null} for an email challenge. Never logged (S-4). */
    @Column(name = "phone", length = 20)
    private String phone;

    /** Target email, or {@code null} for an SMS challenge. Never logged (S-4). */
    @Column(name = "email", length = 254)
    private String email;

    /** The flow this challenge authorises (signup/login/verify). */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 16)
    private OtpPurpose purpose;

    /** The delivery channel (SMS/email). */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 8)
    private OtpChannel channel;

    /**
     * Keyed HMAC-SHA-256 of the OTP code (via {@code CryptoPort.blindIndex}) — never the plaintext
     * code (S-4, PRD §18). Verification hashes the presented code and compares constant-time-ish by
     * equality of the deterministic hash.
     */
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    /** Expiry instant (UTC); a verify after this fails closed (TTL ~5 min, §25.1 "minutes"). */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Number of verify attempts made so far; capped by {@link #maxAttempts}. */
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** Maximum verify attempts before the challenge is burned (default 5). */
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    /** Whether this challenge has been consumed (single-use); a consumed challenge never re-verifies. */
    @Column(name = "consumed", nullable = false)
    private boolean consumed = false;

    /** The owning account (FK), or {@code null} for signup-before-account. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** JPA requires a no-arg constructor; not for application use. */
    protected OtpChallenge() {
    }

    /**
     * Creates a new, unconsumed OTP challenge.
     *
     * @param phone       target phone (E.164) for an SMS challenge, or {@code null}.
     * @param email       target email for an email challenge, or {@code null}.
     * @param purpose     the flow this challenge authorises.
     * @param channel     the delivery channel.
     * @param codeHash    the keyed hash of the generated code (never the plaintext — S-4).
     * @param expiresAt   the TTL expiry instant (UTC).
     * @param maxAttempts the verify-attempt cap.
     * @param user        the owning account, or {@code null} for signup-before-account.
     * @return the populated, transient challenge ready to persist.
     */
    public static OtpChallenge create(String phone, String email, OtpPurpose purpose, OtpChannel channel,
                                      String codeHash, Instant expiresAt, int maxAttempts, User user) {
        OtpChallenge c = new OtpChallenge();
        c.phone = phone;
        c.email = email;
        c.purpose = purpose;
        c.channel = channel;
        c.codeHash = codeHash;
        c.expiresAt = expiresAt;
        c.maxAttempts = maxAttempts;
        c.user = user;
        return c;
    }

    /**
     * Records a failed verify attempt (increments the counter).
     *
     * @return {@code true} if the attempt cap is now reached (the challenge should be treated as burned).
     */
    public boolean registerFailedAttempt() {
        this.attempts++;
        return this.attempts >= this.maxAttempts;
    }

    /** Marks this challenge consumed (single-use); called on a successful verify. */
    public void consume() {
        this.consumed = true;
    }

    /**
     * @param now the current instant.
     * @return whether this challenge can still be verified: not consumed, attempts under cap, not expired.
     */
    public boolean isVerifiable(Instant now) {
        return !consumed && attempts < maxAttempts && now.isBefore(expiresAt);
    }

    /** @return the target phone, or {@code null}. Handle as PII — never log. */
    public String getPhone() {
        return phone;
    }

    /** @return the target email, or {@code null}. Handle as PII — never log. */
    public String getEmail() {
        return email;
    }

    /** @return the flow this challenge authorises. */
    public OtpPurpose getPurpose() {
        return purpose;
    }

    /** @return the delivery channel. */
    public OtpChannel getChannel() {
        return channel;
    }

    /** @return the keyed hash of the code (never the plaintext). */
    public String getCodeHash() {
        return codeHash;
    }

    /** @return the TTL expiry instant. */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** @return the number of verify attempts made. */
    public int getAttempts() {
        return attempts;
    }

    /** @return the verify-attempt cap. */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** @return whether this challenge has been consumed. */
    public boolean isConsumed() {
        return consumed;
    }

    /** @return the owning account, or {@code null} for signup-before-account. */
    public User getUser() {
        return user;
    }
}
