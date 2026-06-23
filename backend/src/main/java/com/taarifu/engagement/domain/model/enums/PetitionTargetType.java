package com.taarifu.engagement.domain.model.enums;

/**
 * What a {@link com.taarifu.engagement.domain.model.Petition} is addressed to (PRD §9.1, §9 ERD).
 *
 * <p>Responsibility: discriminates the {@code targetId} on a petition between an elected
 * representative (Mbunge/Diwani) and an institution/office (Halmashauri, ministry, agency). The
 * concrete target lives in the <b>institutions</b> module; this module references it by {@code UUID}
 * only and never imports institutions (HARD ISOLATION rule 2). The type tells callers/integrators
 * which registry the {@code targetId} resolves against.</p>
 *
 * <p>WHY a type discriminator rather than two nullable FKs: keeps the petition row simple and the
 * cross-module reference late-bound (UUID + type), so wiring to institutions is a later integration
 * step without a schema change.</p>
 */
public enum PetitionTargetType {

    /** Target is an elected representative (MP/Councillor); {@code targetId} = a Representative public id. */
    REPRESENTATIVE,

    /** Target is an institution/office (council, ministry, agency); {@code targetId} = an Office public id. */
    OFFICE
}
