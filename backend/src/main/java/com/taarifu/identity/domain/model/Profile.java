package com.taarifu.identity.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.common.infrastructure.persistence.EncryptedStringConverter;
import com.taarifu.identity.domain.model.enums.IdType;
import com.taarifu.identity.domain.model.enums.ProfileType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The human or organisation identity behind a {@link User} account (PRD §6.3, §9.1).
 *
 * <p>Responsibility: holds names, contacts, demographics, the government ID, and verification flags.
 * One-to-one with {@link User}. The government ID is the most sensitive PII on the platform and is
 * handled with two columns, by design (PRD §18, D15):</p>
 * <ul>
 *   <li>{@link #idNo} — <b>field-level encrypted</b> at rest via {@link EncryptedStringConverter}
 *       (the DB stores ciphertext only); decrypted only when lawfully needed (PDPA).</li>
 *   <li>{@link #idHash} — a deterministic <b>blind index</b> over {@code idType + idNo}, <b>unique</b>,
 *       used for one-person-one-account dedup <i>without decrypting</i> any ID (D15). Computed by the
 *       application via {@code CryptoPort.blindIndex(...)} on write.</li>
 * </ul>
 *
 * <p>WHY a unique blind index rather than a unique constraint on the encrypted column: the encrypted
 * column is randomised (different ciphertext each write) and cannot enforce uniqueness or be searched
 * by equality; the deterministic hash can — so dedup is possible while the plaintext stays encrypted
 * (PRD §18). The uniqueness of {@code idHash} is the hard guarantee that blocks a duplicate identity.</p>
 *
 * <p>WHY no controllers/services in this increment: identity is the data layer only here
 * (FOUNDATION-SCOPE.md §5); verification-flag transitions and dedup logic land in the auth increment.
 * The columns and constraints exist now so that increment is pure behaviour.</p>
 */
@Entity
@Table(name = "profile",
        uniqueConstraints = {
                // Dedup: at most one profile per (idType, idNo) — enforced via the blind-index hash.
                @UniqueConstraint(name = "ux_profile_id_hash", columnNames = "id_hash")
        },
        indexes = {
                @Index(name = "ix_profile_user", columnList = "user_id", unique = true),
                @Index(name = "ix_profile_type", columnList = "type")
        })
@SQLRestriction("deleted = false")
public class Profile extends BaseEntity {

    /** The owning account (1:1). A real FK, never a loose id (fixes legacy, PRD §6.3). */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /** Whether this profile is a natural person or an organisation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private ProfileType type = ProfileType.PERSON;

    /** Given/first name (person) or org name. */
    @Column(name = "first_name", length = 120)
    private String firstName;

    /** Family/last name (person); {@code null} for organisations. */
    @Column(name = "last_name", length = 120)
    private String lastName;

    /** Type of the government ID document (NIDA/voter/passport), or {@code null} if not provided. */
    @Enumerated(EnumType.STRING)
    @Column(name = "id_type", length = 16)
    private IdType idType;

    /**
     * Government ID number — <b>stored encrypted</b> via {@link EncryptedStringConverter}. The column
     * holds ciphertext; never log or expose this value (PRD §18, PDPA).
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "id_no", length = 512)
    private String idNo;

    /**
     * Deterministic blind index over {@code idType + ":" + idNo} (unique) driving one-person-one-account
     * dedup without decryption (D15). Set by the application on write; not human-readable.
     */
    @Column(name = "id_hash", length = 64, unique = true)
    private String idHash;

    /** Verification flag: government ID verified (gates trust-tier T3). */
    @Column(name = "id_verified", nullable = false)
    private boolean idVerified = false;

    /** Verification flag: email verified. */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    /** Verification flag: phone verified (gates trust-tier T1). */
    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    /** When verification last succeeded (UTC), or {@code null}. */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    /**
     * <b>Profile-anchored</b> last electoral-change instant (UTC), backing the manual {@code isElectoral}
     * change cooldown (D13). {@code null} until the first electoral is set.
     *
     * <p>WHY on the profile and not on {@link ProfileLocation} (review V-1, P2): the cooldown used to read
     * {@code electoral_changed_at} off the <b>current electoral row</b>, which a citizen could soft-delete
     * (remove the electoral pin) then set a different pin electoral — the new row's timestamp was
     * {@code null}, the cooldown was skipped, and the move went through immediately, reopening the
     * cross-location double-influence D13 exists to prevent. The profile cannot be deleted by the citizen,
     * so anchoring the cooldown here makes it unbypassable. Stamped on <b>every</b> electoral set/change —
     * manual <b>and</b> voter-ID-authoritative — via {@link #stampElectoralChange(Instant)}.</p>
     */
    @Column(name = "electoral_changed_at")
    private Instant electoralChangedAt;

    /** Date of birth (person demographics); {@code null} for organisations/unset. */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** Gender (free-form/coded per reference data); optional. */
    @Column(name = "gender", length = 16)
    private String gender;

    /** Nationality code; optional (diaspora support). */
    @Column(name = "nationality", length = 3)
    private String nationality;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Profile() {
    }

    /**
     * Creates a natural-person profile for an account, with phone already verified (the signup path —
     * a citizen reaches a phone-verified profile at T1, AUTH-DESIGN §3).
     *
     * @param user the owning account (1:1).
     * @return the populated, transient profile.
     */
    public static Profile createPersonForSignup(User user) {
        Profile p = new Profile();
        p.user = user;
        p.type = ProfileType.PERSON;
        p.phoneVerified = true;
        return p;
    }

    /**
     * Updates the profile's name/demographic fields during profile completion (AUTH-DESIGN §6).
     * Only the named fields are touched; {@code null} arguments leave the existing value unchanged so a
     * PATCH semantics holds. Never logs values (S-4).
     *
     * @param firstName    given/first name, or {@code null} to leave unchanged.
     * @param lastName     family name, or {@code null} to leave unchanged.
     * @param dateOfBirth  date of birth, or {@code null} to leave unchanged.
     * @param gender       gender, or {@code null} to leave unchanged.
     * @param nationality  nationality code, or {@code null} to leave unchanged.
     */
    public void updateDetails(String firstName, String lastName, java.time.LocalDate dateOfBirth,
                              String gender, String nationality) {
        if (firstName != null) {
            this.firstName = firstName;
        }
        if (lastName != null) {
            this.lastName = lastName;
        }
        if (dateOfBirth != null) {
            this.dateOfBirth = dateOfBirth;
        }
        if (gender != null) {
            this.gender = gender;
        }
        if (nationality != null) {
            this.nationality = nationality;
        }
    }

    /** Marks the profile's email verified (after an EMAIL OTP verify — AUTH-DESIGN §6). */
    public void markEmailVerified() {
        this.emailVerified = true;
        this.verifiedAt = Instant.now();
    }

    /** Marks the profile's phone verified (signup / re-verify). */
    public void markPhoneVerified() {
        this.phoneVerified = true;
    }

    /**
     * Records the submitted government identity at verification submit (Flow 2). The {@code idNo} is
     * encrypted by the column converter; the {@code idHash} is the blind index that backs the unique
     * dedup constraint (D15). This does <b>not</b> set {@code idVerified} — submitting is not being
     * verified (tier is unchanged until a reviewer approves, PRD §25.5). The plaintext {@code idNo} is
     * never logged (S-4).
     *
     * @param idType the government ID document type (NIDA/voter/passport).
     * @param idNo   the document number (PII — stored encrypted, never logged).
     * @param idHash the deterministic blind index over {@code idType + ":" + idNo} (D15).
     */
    public void setIdentity(IdType idType, String idNo, String idHash) {
        this.idType = idType;
        this.idNo = idNo;
        this.idHash = idHash;
    }

    /**
     * Marks the government ID verified (on Moderator approval — Flow 3). Setting this flip is what makes
     * the <b>live</b> {@code TierService} return T3 the next time it resolves (MF-2) — no tier is set
     * here directly. Stamps {@link #verifiedAt}.
     *
     * @param now the verification instant (UTC, from the injected clock).
     */
    public void markIdVerified(Instant now) {
        this.idVerified = true;
        this.verifiedAt = now;
    }

    /**
     * Revokes ID verification (fraud/erasure — §25.5). The live tier immediately drops back to T2 on the
     * next resolve (no token reissue needed, since the tier claim is never trusted, MF-2); prior actions
     * taken while T3 stand (PRD §25.5).
     */
    public void revokeIdVerified() {
        this.idVerified = false;
    }

    /**
     * <b>Crypto-shreds and tombstones</b> this profile on a verified erasure request (PRD §25.1,
     * UC-A17/UC-S09; ADR-0016 §5). This is the highest-sensitivity severing on the platform.
     *
     * <p>What it does (and WHY each step):</p>
     * <ul>
     *   <li><b>Crypto-shred the national/voter ID:</b> nulls the encrypted {@link #idNo} ciphertext
     *       <b>and</b> the deterministic {@link #idHash} blind index, plus the {@link #idType}. The column
     *       held only ciphertext; nulling it together with the dedup hash removes both the value and the
     *       identity linkage — the strongest per-row severing available (a future KMS adapter may also retire
     *       the data key — a CENTRAL NEED). WHY also the hash: the blind index is a (deterministic) derivative
     *       of the ID and is itself a re-identification vector, so it must go too.</li>
     *   <li><b>Replace names with a tombstone label</b> ({@code anonymized_user_<short>}) and null
     *       date-of-birth/gender/nationality — the person is removed but the row persists so the de-identified
     *       civic record (reports, signature counts, ratings) stays referentially intact (§25.1).</li>
     *   <li><b>Reset verification flags</b> — an anonymised profile is no longer a verified identity.</li>
     * </ul>
     *
     * <p>It does <b>not</b> delete the row (one-account permanence, D15/§6.4) and does <b>not</b> touch the
     * audit log (the caller appends an {@code IDENTITY_ERASED} tombstone — the hash-chain is never broken).
     * The plaintext ID is never logged at any point (S-4).</p>
     *
     * @param tombstoneLabel the {@code anonymized_user_<short>} display label to replace the names with.
     */
    public void anonymise(String tombstoneLabel) {
        // Crypto-shred the national/voter ID: ciphertext + blind index + type.
        this.idNo = null;
        this.idHash = null;
        this.idType = null;
        // Sever the remaining person PII; keep the row (de-identified civic record persists).
        this.firstName = tombstoneLabel;
        this.lastName = null;
        this.dateOfBirth = null;
        this.gender = null;
        this.nationality = null;
        // An anonymised profile is no longer a verified identity.
        this.idVerified = false;
        this.emailVerified = false;
        // phoneVerified left as-is is meaningless post-erasure; reset for cleanliness.
        this.phoneVerified = false;
    }

    /**
     * @return {@code true} if this profile has been crypto-shredded/tombstoned by erasure (the idempotency
     *         guard for the at-least-once erasure handler — ADR-0016 §5): both the encrypted ID and its blind
     *         index are null. A redelivered {@code ERASURE_REQUESTED} sees this and no-ops.
     */
    public boolean isAnonymised() {
        return this.idNo == null && this.idHash == null && this.idType == null;
    }

    /**
     * Composes the profile's <b>public display name</b> — the non-sensitive, human-recognisable label other
     * surfaces show for this identity: a person's {@code firstName + " " + lastName} (trimmed), or an
     * organisation's name (carried in {@code firstName}). Single source of truth for "what name do we show?",
     * reused by the admin user view and the cross-module {@code ProfileLookupApi}/{@code SubjectContentQueryApi}
     * read ports so the composition rule never drifts (DRY).
     *
     * <p><b>🔒 PII discipline (PRD §18, PDPA):</b> the display name is civic display data deliberately collected
     * to be shown — it is <b>not</b> sensitive ID PII. This method exposes only names; it <b>never</b> touches the
     * national/voter {@link #idNo} (encrypted), the {@link #idHash} blind index, phone, or any other PII. After
     * an erasure {@link #anonymise(String)} the first name holds the {@code anonymized_user_<short>} tombstone
     * label and the last name is null, so this correctly returns the tombstone — never resurrected PII.</p>
     *
     * @return the trimmed display name, or {@code null} if no name has been set yet (a profile that has not
     *         completed its details) — callers treat {@code null} as "name unknown", never as an error.
     */
    public String displayName() {
        String first = firstName == null ? "" : firstName;
        String last = lastName == null ? "" : lastName;
        String name = (first + " " + last).trim();
        return name.isEmpty() ? null : name;
    }

    /** @return the owning account. */
    public User getUser() {
        return user;
    }

    /** @return person vs organisation. */
    public ProfileType getType() {
        return type;
    }

    /** @return given/first name or org name. */
    public String getFirstName() {
        return firstName;
    }

    /** @return family name, or {@code null} for organisations. */
    public String getLastName() {
        return lastName;
    }

    /** @return the government ID document type, or {@code null}. */
    public IdType getIdType() {
        return idType;
    }

    /** @return the decrypted government ID number (handle as PII — never log), or {@code null}. */
    public String getIdNo() {
        return idNo;
    }

    /** @return the blind-index dedup hash, or {@code null} if no ID recorded. */
    public String getIdHash() {
        return idHash;
    }

    /** @return whether the government ID is verified. */
    public boolean isIdVerified() {
        return idVerified;
    }

    /** @return whether email is verified. */
    public boolean isEmailVerified() {
        return emailVerified;
    }

    /** @return whether phone is verified. */
    public boolean isPhoneVerified() {
        return phoneVerified;
    }

    /** @return last successful verification instant, or {@code null}. */
    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    /**
     * Stamps the profile-anchored electoral-change instant (review V-1, D13). Called on <b>every</b>
     * electoral set/change — manual and voter-ID-authoritative — so the manual-change cooldown is measured
     * from this profile-level instant, which survives a citizen deleting/re-adding the electoral pin
     * (closing the remove-then-re-add bypass).
     *
     * @param now the change instant (UTC, from the injected clock).
     */
    public void stampElectoralChange(Instant now) {
        this.electoralChangedAt = now;
    }

    /** @return the profile-anchored last electoral-change instant (cooldown anchor, D13), or {@code null}. */
    public Instant getElectoralChangedAt() {
        return electoralChangedAt;
    }

    /** @return date of birth, or {@code null}. */
    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    /** @return gender, or {@code null}. */
    public String getGender() {
        return gender;
    }

    /** @return nationality code, or {@code null}. */
    public String getNationality() {
        return nationality;
    }
}
