package com.taarifu.institutions.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * A named parliamentary role/office assignable to a representative — e.g. Speaker (Spika), Minister
 * (Waziri), Deputy Speaker, Committee Chair (PRD §9.1, §22.6).
 *
 * <p>Responsibility: the reference catalogue of roles a {@link Representative} may additionally hold on
 * top of their seat. A representative optionally FKs one such role (e.g. an MP who is also Speaker). It
 * is a small, admin-curated lookup, kept as its own entity (not a free-text field) so the set is
 * consistent and filterable across the directory (DRY — the same reasoning as {@link PoliticalParty}).</p>
 *
 * <p>WHY a flat catalogue rather than a per-representative many-to-many for the MVP: at launch a
 * representative surfaces at most one headline office in their profile/directory card (PRD §22.6); the
 * single-FK keeps the read model lean for feature-phone payloads (PRD §15). A richer
 * representative↔role assignment history is an accountability (Phase 2) concern, not this slice.</p>
 */
@Entity
@Table(name = "parliament_role", indexes = {
        @Index(name = "ix_parliament_role_code", columnList = "code", unique = true)
})
@SQLRestriction("deleted = false")
public class ParliamentRole extends BaseEntity {

    /** Stable role code; unique; idempotent seed/import match key (e.g. {@code SPEAKER}, {@code MINISTER}). */
    @Column(name = "code", nullable = false, unique = true, length = 48)
    private String code;

    /** Display name of the role (e.g. "Speaker", "Minister of Health"). */
    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /** Optional description of the role's remit (reference attribute). */
    @Column(name = "description", length = 512)
    private String description;

    /** JPA requires a no-arg constructor; not for application use. */
    protected ParliamentRole() {
    }

    /**
     * Factory for a new, empty parliament role — the only sanctioned construction path.
     *
     * @return a fresh, unpopulated role.
     */
    public static ParliamentRole create() {
        return new ParliamentRole();
    }

    /**
     * Sets the immutable identity {@code code}; only at creation (idempotent key — see {@link
     * PoliticalParty#assignCode}).
     *
     * @param code the stable role code.
     */
    public void assignCode(String code) {
        this.code = code;
    }

    /**
     * Applies an admin write of the mutable attributes.
     *
     * @param name        role display name.
     * @param description role remit, or {@code null}.
     */
    public void applyDetails(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /** @return the stable role code (unique seed/import key). */
    public String getCode() {
        return code;
    }

    /** @return the role display name. */
    public String getName() {
        return name;
    }

    /** @return the optional role description, or {@code null}. */
    public String getDescription() {
        return description;
    }
}
