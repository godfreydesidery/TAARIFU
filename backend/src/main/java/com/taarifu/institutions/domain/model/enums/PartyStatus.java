package com.taarifu.institutions.domain.model.enums;

/**
 * Lifecycle status of a {@link com.taarifu.institutions.domain.model.PoliticalParty}
 * (PRD §9.1).
 *
 * <p>Responsibility: distinguishes a party that is currently registered and active from one that has
 * been deregistered/dissolved. WHY a status rather than a physical delete: party affiliation is part
 * of a representative's <b>historical</b> civic record — a {@code FORMER} MP elected under a party that
 * later dissolved must still display that affiliation faithfully (PRD §9.1, §22.6). Soft-delete (the
 * {@code BaseEntity} tombstone) is a separate, administrative concern; this is the civic lifecycle.</p>
 */
public enum PartyStatus {

    /** Registered and currently operating. */
    ACTIVE,

    /** Deregistered, dissolved, or merged away; retained for historical affiliation. */
    INACTIVE
}
