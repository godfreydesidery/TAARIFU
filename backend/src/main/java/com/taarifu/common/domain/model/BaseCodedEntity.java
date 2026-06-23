package com.taarifu.common.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/**
 * Persistence base for entities that additionally carry a <b>human-readable code</b>
 * (ARCHITECTURE.md §4.2, ADR-0006).
 *
 * <p>Responsibility: extends {@link BaseEntity} with a {@code code} column for the handful of
 * entities a user must quote out loud — e.g. a report ticket {@code TAR-2026-000123}. The code is
 * populated from a <b>database sequence</b> via {@code common.persistence.CodeGenerator}, never from
 * {@code max(id)+1} (race-prone) and never reused (history is stable, ADR-0006).</p>
 *
 * <p>Three distinct identifiers, by design (do not conflate):</p>
 * <ul>
 *   <li>internal {@code Long id} — FK/join key (from {@link BaseEntity});</li>
 *   <li>{@code UUID publicId} — machine/public id in URLs (from {@link BaseEntity});</li>
 *   <li>{@code String code} — human display id (this class).</li>
 * </ul>
 *
 * <p>WHY this is opt-in (a separate base rather than a field on {@link BaseEntity}): most entities
 * never need a spoken code; adding the column everywhere would be waste and noise (KISS/DRY,
 * CLAUDE.md §3). No coded entity exists in this foundation increment — the base is provided so the
 * reporting increment (which introduces {@code Report.code}) has the established pattern.</p>
 */
@MappedSuperclass
public abstract class BaseCodedEntity extends BaseEntity {

    /**
     * Human-readable, sequence-derived display code (e.g. {@code TAR-2026-000123}).
     *
     * <p>Unique and immutable once issued. Populated by the application's {@code CodeGenerator}
     * before persist (the generation hook lives in the owning module's service, not here, so the
     * sequence/format is per-entity-type — ADR-0006).</p>
     */
    @Column(name = "code", updatable = false, unique = true)
    private String code;

    /** @return the human-readable display code, or {@code null} before it is assigned. */
    public String getCode() {
        return code;
    }

    /**
     * Assigns the display code. Intended to be called exactly once by the owning module's
     * code-generation step before the entity is first persisted.
     *
     * @param code the sequence-derived code to set.
     */
    public void setCode(String code) {
        this.code = code;
    }
}
