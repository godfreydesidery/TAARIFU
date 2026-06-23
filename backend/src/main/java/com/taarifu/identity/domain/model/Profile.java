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
