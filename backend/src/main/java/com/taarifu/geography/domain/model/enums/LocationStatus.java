package com.taarifu.geography.domain.model.enums;

/**
 * Lifecycle status of an administrative {@code Location} (PRD §9.1).
 *
 * <p>Responsibility: lets re-delimitation (new districts, merged wards) be modelled without deleting
 * history — a superseded unit is marked {@link #INACTIVE} rather than removed, so reports/profiles
 * pinned to it remain valid and auditable (PRD §9.0, EI-14). Soft-delete on {@code BaseEntity} handles
 * erroneous rows; this status captures <i>civic</i> retirement, which is a different concept.</p>
 */
public enum LocationStatus {

    /** Currently in use for pinning, routing, and resolution. */
    ACTIVE,

    /** Civically retired (e.g. superseded by re-delimitation); retained for history, not for new pins. */
    INACTIVE
}
