package com.taarifu.identity.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.common.infrastructure.persistence.EncryptedStringConverter;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.model.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * A platform account — the authentication subject (PRD §6.3, §9.1, §6.4 [DECIDED]).
 *
 * <p>Responsibility: holds login credentials and account-level state. Exactly <b>one account per
 * person</b>, enforced by a <b>unique phone</b> at signup plus national/voter-ID dedup at verification
 * (D11/D15). Roles are <b>additive</b> on this one account (§6.4) and live in {@link RoleAssignment};
 * the human/organisation identity lives in {@link Profile} (1:1). The JWT subject is this entity's
 * {@code publicId} — never the mutable phone/handle (ADR-0006/0007).</p>
 *
 * <p>WHY phone is the uniqueness key (not email): in the Tanzanian context a mobile number is the
 * near-universal identifier and the OTP signup anchor; an existing phone offers login/recovery, never a
 * second account (D11/D15, US-0.1). Email is optional and not unique here.</p>
 *
 * <p>WHY no roles/profile collections are mapped here in this increment: this is the data layer only
 * (FOUNDATION-SCOPE.md §5); associations are navigated from the owning side ({@link RoleAssignment},
 * {@link Profile}) to keep the aggregate boundaries explicit and avoid eager fan-out (KISS).</p>
 *
 * <p>{@code password_hash} stores a <b>BCrypt</b> hash only — never plaintext (ADR-0007). PII is not
 * stored on {@code User}; identity PII lives on {@link Profile} (field-encrypted).</p>
 */
@Entity
@Table(name = "app_user", indexes = {
        @Index(name = "ux_app_user_phone", columnList = "phone", unique = true),
        @Index(name = "ix_app_user_email", columnList = "email"),
        @Index(name = "ix_app_user_status", columnList = "status")
})
@SQLRestriction("deleted = false")
public class User extends BaseEntity {

    /**
     * Mobile phone in E.164 form; the <b>unique</b> account key (one account per phone — D11/D15).
     * Not null; the unique index is the hard integrity guarantee.
     */
    @Column(name = "phone", nullable = false, unique = true, length = 20)
    private String phone;

    /** Optional email (login alias / notifications); not unique. */
    @Column(name = "email", length = 254)
    private String email;

    /** Optional public handle/username; display only — never an authorization key (ADR-0006). */
    @Column(name = "handle", length = 64)
    private String handle;

    /** BCrypt password hash; {@code null} for OTP-only accounts. Never plaintext (ADR-0007). */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /** Account lifecycle status (can this account authenticate/act). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserStatus status = UserStatus.PENDING;

    /**
     * Cached trust tier T0–T3 (PRD §7.3). A convenience/claim source only — high-stakes actions
     * re-resolve the live tier server-side; the cached value is never the sole authorization input
     * (PRD §17, §18).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "trust_tier", nullable = false, length = 4)
    private TrustTier trustTier = TrustTier.T0;

    /** Whether TOTP MFA is enabled (staff accounts; PRD §18, EI-15). */
    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled = false;

    /**
     * The <b>active</b> Base32 TOTP secret — set on {@code activate}, used to verify the staff second
     * factor (N-4). <b>Field-encrypted</b> at rest via {@link EncryptedStringConverter} (the DB stores
     * ciphertext); never logged or serialised (S-4). {@code null} until MFA is activated.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mfa_totp_secret", length = 512)
    private String mfaTotpSecret;

    /**
     * A <b>provisional</b> Base32 TOTP secret set at {@code setup}, <b>before</b> activation —
     * field-encrypted, never logged (S-4). Kept separate from {@link #mfaTotpSecret} so an un-activated
     * secret can never satisfy a login; {@code activate} promotes it to the active secret (§2.3).
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mfa_pending_secret", length = 512)
    private String mfaPendingSecret;

    /** Last successful login instant (UTC); {@code null} until first login. */
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * The highest accepted TOTP <b>time-step</b> ({@code epochSeconds / stepSeconds}) for this account's
     * staff second factor; {@code null} until the first code is accepted (treated as {@code -1}).
     *
     * <p>WHY (review V-2, P2): the staff TOTP second factor was replayable — a captured
     * {@code (mfaToken, totp)} pair could be redeemed more than once inside the step window to mint extra
     * token families. Tracking the last accepted step and refusing any code whose step {@code <=} it makes
     * the factor <b>single-use</b> (standard TOTP replay defence): the first login redemption advances this
     * watermark, so the identical code (same step) cannot be accepted again. Advanced <b>only</b> on a
     * successful login TOTP step — never on {@code activate}, so a citizen completing their first login in
     * the same step as enrolment is not mis-flagged as a replay. Not PII.</p>
     */
    @Column(name = "last_totp_step")
    private Long lastTotpStep;

    /** JPA requires a no-arg constructor; not for application use. */
    protected User() {
    }

    /**
     * Creates a new account in {@link UserStatus#PENDING} at {@link TrustTier#T0} (the signup entry
     * state). Status and tier are promoted by the application after OTP verification; the client never
     * sets them (AUTH-DESIGN §3, MF-2).
     *
     * @param phone the unique E.164 phone (the one-account-per-phone key — D11/D15).
     * @return the populated, transient account.
     */
    public static User createPending(String phone) {
        User u = new User();
        u.phone = phone;
        u.status = UserStatus.PENDING;
        u.trustTier = TrustTier.T0;
        return u;
    }

    /** Marks the account active (e.g. after OTP signup verification). */
    public void activate() {
        this.status = UserStatus.ACTIVE;
    }

    /**
     * Sets the cached trust tier. This is a <b>convenience/claim source only</b>; high-stakes gating
     * always re-resolves the live tier from the DB ({@code TierService}/MF-2), so a stale cached value
     * can never authorise an action.
     *
     * @param trustTier the newly computed tier.
     */
    public void setTrustTier(TrustTier trustTier) {
        this.trustTier = trustTier;
    }

    /**
     * Sets the BCrypt password hash (optional; OTP-only accounts leave it {@code null}).
     *
     * @param passwordHash a BCrypt hash — never plaintext (ADR-0007).
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** Sets the optional email (login alias / notifications). */
    public void setEmail(String email) {
        this.email = email;
    }

    /** Records the instant of a successful login. */
    public void recordLogin(Instant when) {
        this.lastLoginAt = when;
    }

    /**
     * Stores the provisional TOTP secret produced at {@code setup} (before activation). Encrypted at
     * rest by the column converter; never logged (S-4).
     *
     * @param mfaPendingSecret the Base32 secret, or {@code null} to clear it.
     */
    public void setMfaPendingSecret(String mfaPendingSecret) {
        this.mfaPendingSecret = mfaPendingSecret;
    }

    /** @return the provisional (un-activated) TOTP secret, or {@code null}. Handle as a secret — never log. */
    public String getMfaPendingSecret() {
        return mfaPendingSecret;
    }

    /** @return the active TOTP secret used to verify the staff second factor, or {@code null}. Never log. */
    public String getMfaTotpSecret() {
        return mfaTotpSecret;
    }

    /**
     * Activates MFA: promotes the provisional secret to the active secret, clears the pending slot, and
     * sets {@code mfaEnabled=true} (§2.3). After this the account must complete a TOTP step to hold a
     * staff session (N-4).
     *
     * @throws IllegalStateException if no provisional secret was set by a prior {@code setup}.
     */
    public void enableMfa() {
        if (this.mfaPendingSecret == null) {
            throw new IllegalStateException("No pending TOTP secret to activate");
        }
        this.mfaTotpSecret = this.mfaPendingSecret;
        this.mfaPendingSecret = null;
        this.mfaEnabled = true;
    }

    /** @return the unique E.164 phone (account key). */
    public String getPhone() {
        return phone;
    }

    /** @return the optional email. */
    public String getEmail() {
        return email;
    }

    /** @return the optional display handle. */
    public String getHandle() {
        return handle;
    }

    /** @return the BCrypt password hash, or {@code null} for OTP-only accounts. */
    public String getPasswordHash() {
        return passwordHash;
    }

    /** @return the account lifecycle status. */
    public UserStatus getStatus() {
        return status;
    }

    /** @return the cached trust tier (re-checked live for high-stakes actions). */
    public TrustTier getTrustTier() {
        return trustTier;
    }

    /** @return whether MFA is enabled. */
    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    /** @return the last login instant, or {@code null}. */
    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    /**
     * @return the highest accepted TOTP time-step, or {@code -1} if no code has been accepted yet. The
     *         sentinel keeps the "reject step &lt;= last" replay check total without a null branch at the
     *         call site (review V-2).
     */
    public long getLastTotpStep() {
        return lastTotpStep == null ? -1L : lastTotpStep;
    }

    /**
     * Advances the TOTP replay watermark to a newly accepted step (review V-2, N-4). The caller has already
     * verified the step is strictly greater than {@link #getLastTotpStep()}; persisting it here means the
     * same code (same step) can never be accepted twice — the staff second factor is single-use.
     *
     * @param step the just-accepted TOTP time-step ({@code epochSeconds / stepSeconds}).
     */
    public void advanceLastTotpStep(long step) {
        this.lastTotpStep = step;
    }
}
