package com.taarifu.institutions.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.institutions.domain.model.enums.PartyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * A registered political party (Chama cha Siasa) — reference-directory entity (PRD §9.1, §22.6).
 *
 * <p>Responsibility: the canonical party record a {@link Representative} is affiliated with, plus the
 * public party directory. Holds the descriptive/reference attributes only; it is <b>not</b> a security
 * principal and carries no PII.</p>
 *
 * <p>WHY a real entity with a FK from {@code Representative} (not a free-text party string on each rep):
 * the legacy model duplicated party names per representative, which drifted and broke "all reps of party
 * X" queries. One party row, referenced by FK, is the single source of truth (DRY, CLAUDE.md §3) and
 * lets the directory and per-party filters be exact (PRD §9.1).</p>
 *
 * <p>WHY {@code logoRef} is a storage <i>reference</i> (an object-store key/URL), not bytes: binary
 * assets live in object storage behind the {@code ObjectStore} port, never in the row (ARCHITECTURE.md
 * §7). {@code status} ({@link PartyStatus}) models civic deregistration distinctly from the soft-delete
 * tombstone so a dissolved party still anchors a former rep's historical affiliation (PRD §22.6).</p>
 */
@Entity
@Table(name = "political_party", indexes = {
        @Index(name = "ix_political_party_code", columnList = "code", unique = true),
        @Index(name = "ix_political_party_status", columnList = "status")
})
@SQLRestriction("deleted = false")
public class PoliticalParty extends BaseEntity {

    /** Official party code/abbreviation key; unique; idempotent match key for seed/bulk import. */
    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    /** Full registered party name (e.g. "Chama Cha Mapinduzi"). */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Common short form / acronym (e.g. "CCM", "CHADEMA"); display-facing, not necessarily unique. */
    @Column(name = "abbreviation", length = 32)
    private String abbreviation;

    /** Optional stated ideology/political position (free text, reference attribute). */
    @Column(name = "ideology", length = 160)
    private String ideology;

    /** Optional year the party was founded (reference attribute). */
    @Column(name = "founded_year")
    private Integer foundedYear;

    /**
     * Optional reference (object-store key/URL) to the party logo asset. WHY a reference, not bytes:
     * binary lives in object storage behind a port; the row stays small (ARCHITECTURE.md §7).
     */
    @Column(name = "logo_ref", length = 512)
    private String logoRef;

    /** Civic lifecycle status; a deregistered party becomes {@link PartyStatus#INACTIVE}, not deleted. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PartyStatus status = PartyStatus.ACTIVE;

    /** Optional public contact details (phone/email/website), free text; never citizen PII. */
    @Column(name = "contacts", length = 512)
    private String contacts;

    /** JPA requires a no-arg constructor; not for application use. */
    protected PoliticalParty() {
    }

    /**
     * Factory for a new, empty party — the only sanctioned way the admin service constructs one (the
     * constructor stays {@code protected} so the entity cannot be instantiated ad hoc across the code).
     *
     * @return a fresh, unpopulated party (caller then {@link #assignCode}/{@link #applyDetails}).
     */
    public static PoliticalParty create() {
        return new PoliticalParty();
    }

    /**
     * Sets the immutable identity {@code code}. WHY only at creation: the code is the idempotent
     * seed/import key; the admin service rejects any change on update so existing references never orphan.
     *
     * @param code the official party code.
     */
    public void assignCode(String code) {
        this.code = code;
    }

    /**
     * Applies the mutable reference attributes from an admin write. WHY a single mutator (not per-field
     * setters): the party is mutated only by the admin service, in one coherent call, keeping the write
     * surface small and auditable (CLAUDE.md §8) while leaving the entity read-only to all other callers.
     *
     * @param name         full registered name.
     * @param abbreviation short form/acronym, or {@code null}.
     * @param ideology     stated ideology, or {@code null}.
     * @param foundedYear  founding year, or {@code null}.
     * @param logoRef      object-store logo reference, or {@code null}.
     * @param status       civic lifecycle status (never {@code null}).
     * @param contacts     public contacts, or {@code null}.
     */
    public void applyDetails(String name, String abbreviation, String ideology, Integer foundedYear,
                             String logoRef, PartyStatus status, String contacts) {
        this.name = name;
        this.abbreviation = abbreviation;
        this.ideology = ideology;
        this.foundedYear = foundedYear;
        this.logoRef = logoRef;
        this.status = status;
        this.contacts = contacts;
    }

    /** @return the official party code (unique seed/import key). */
    public String getCode() {
        return code;
    }

    /** @return the full registered party name. */
    public String getName() {
        return name;
    }

    /** @return the short form/acronym, or {@code null}. */
    public String getAbbreviation() {
        return abbreviation;
    }

    /** @return the stated ideology, or {@code null}. */
    public String getIdeology() {
        return ideology;
    }

    /** @return the founding year, or {@code null}. */
    public Integer getFoundedYear() {
        return foundedYear;
    }

    /** @return the logo storage reference, or {@code null}. */
    public String getLogoRef() {
        return logoRef;
    }

    /** @return the civic lifecycle status. */
    public PartyStatus getStatus() {
        return status;
    }

    /** @return the public contact details, or {@code null}. */
    public String getContacts() {
        return contacts;
    }
}
