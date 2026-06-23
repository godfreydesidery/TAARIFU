package com.taarifu.ussd.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.ussd.domain.model.enums.UssdLanguage;
import com.taarifu.ussd.domain.model.enums.UssdStep;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * The ephemeral state of one in-flight USSD dialogue, keyed by {@code (msisdn, sessionId)} (PRD §14, EI-4,
 * UC-D02).
 *
 * <p>Responsibility: hold the conversational position ({@link #step}), the chosen {@link #language}, the
 * resolved/created account ({@link #userPublicId}, linked by MSISDN at T1), and the small set of answers
 * accumulated so far in the file-report flow ({@link #categoryId}, {@link #wardId}, {@link #description}).
 * The aggregator is stateless and re-posts every keypress, so this row <b>is</b> the session — the
 * {@code UssdMenuMachine} loads it by key, advances it, and saves it within the webhook's transaction.</p>
 *
 * <p>WHY DB-backed and not Redis: the architecture explicitly permits "Redis-or-DB-backed" ephemeral USSD
 * state (PRD §16, EI-4) and this build has no Redis dependency; a row with an {@link #expiresAt} TTL gives
 * the same self-cleaning, last-write-wins semantics. The store sits behind a port so a later Redis swap is a
 * local change. WHY {@code (msisdn, sessionId)} is unique among live rows: the aggregator guarantees one
 * live session id per dialogue; a duplicate would fork the conversation. The MSISDN is <b>PII</b> — never
 * logged raw (S-4, PDPA); it lives here only to key the session and to link the account.</p>
 *
 * <p>WHY cross-module ids are bare {@code UUID}s (never FKs): {@link #userPublicId}/{@link #categoryId}/
 * {@link #wardId} are owned by identity/reporting/geography; this module references them by public id and
 * resolves/validates them through those modules' ports, never FK-joining (ARCHITECTURE §3.2).</p>
 */
@Entity
@Table(name = "ussd_session",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_ussd_session_key", columnNames = {"msisdn", "session_id"})
        },
        indexes = {
                @Index(name = "ix_ussd_session_expires", columnList = "expires_at")
        })
@SQLRestriction("deleted = false")
public class UssdSession extends BaseEntity {

    /** Caller's phone in E.164 (the session key + the account-link key). PII — never log raw (S-4). */
    @Column(name = "msisdn", nullable = false, length = 20)
    private String msisdn;

    /** Aggregator-assigned session id, unique per live dialogue for this MSISDN. */
    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    /** Current position in the menu state machine. */
    @Enumerated(EnumType.STRING)
    @Column(name = "step", nullable = false, length = 24)
    private UssdStep step;

    /** Language of this dialogue; {@code null} until the first screen is answered (default SW thereafter). */
    @Enumerated(EnumType.STRING)
    @Column(name = "language", length = 4)
    private UssdLanguage language;

    /** Public id of the MSISDN-linked account (identity), resolved/created at session start (T1). Bare ref. */
    @Column(name = "user_public_id")
    private UUID userPublicId;

    /** File-report draft: chosen issue category public id (reporting). Bare ref; null until chosen. */
    @Column(name = "category_id")
    private UUID categoryId;

    /** File-report draft: resolved ward public id (geography), min pin granularity. Bare ref; null until set. */
    @Column(name = "ward_id")
    private UUID wardId;

    /** File-report draft: the citizen's short free-text description; null until typed. */
    @Column(name = "description", length = 1000)
    private String description;

    /**
     * When this session expires and may be reclaimed. An abandoned dialogue self-cleans, matching the
     * aggregator's own short session window (EI-4) and keeping no MSISDN longer than needed (PDPA).
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** JPA requires a no-arg constructor; not for application use. */
    protected UssdSession() {
    }

    /**
     * Starts a fresh session parked on the language screen.
     *
     * @param msisdn    the caller's E.164 phone (PII).
     * @param sessionId the aggregator session id.
     * @param expiresAt when the session should expire.
     * @return the populated, transient session at {@link UssdStep#LANGUAGE}.
     */
    public static UssdSession start(String msisdn, String sessionId, Instant expiresAt) {
        UssdSession s = new UssdSession();
        s.msisdn = msisdn;
        s.sessionId = sessionId;
        s.step = UssdStep.LANGUAGE;
        s.expiresAt = expiresAt;
        return s;
    }

    /** Links the resolved/created MSISDN account (T1) to this session. */
    public void linkUser(UUID userPublicId) {
        this.userPublicId = userPublicId;
    }

    /** Sets the chosen dialogue language. */
    public void setLanguage(UssdLanguage language) {
        this.language = language;
    }

    /** Advances the session to the given step. */
    public void moveTo(UssdStep step) {
        this.step = step;
    }

    /** Records the chosen issue category (file-report draft). */
    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    /** Records the resolved ward (file-report draft). */
    public void setWardId(UUID wardId) {
        this.wardId = wardId;
    }

    /** Records the free-text description (file-report draft). */
    public void setDescription(String description) {
        this.description = description;
    }

    /** Pushes the expiry forward (each interaction keeps a live dialogue alive). */
    public void renew(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    /** @return the caller's E.164 phone (PII — handle with care). */
    public String getMsisdn() {
        return msisdn;
    }

    /** @return the aggregator session id. */
    public String getSessionId() {
        return sessionId;
    }

    /** @return the current menu step. */
    public UssdStep getStep() {
        return step;
    }

    /** @return the dialogue language, or {@code null} before language is chosen. */
    public UssdLanguage getLanguage() {
        return language;
    }

    /** @return the linked account public id, or {@code null} if not yet linked. */
    public UUID getUserPublicId() {
        return userPublicId;
    }

    /** @return the file-report draft category, or {@code null}. */
    public UUID getCategoryId() {
        return categoryId;
    }

    /** @return the file-report draft ward, or {@code null}. */
    public UUID getWardId() {
        return wardId;
    }

    /** @return the file-report draft description, or {@code null}. */
    public String getDescription() {
        return description;
    }

    /** @return when this session expires. */
    public Instant getExpiresAt() {
        return expiresAt;
    }
}
