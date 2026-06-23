package com.taarifu.identity.domain.model.enums;

/**
 * Type of government identity document recorded on a {@code Profile} (PRD §9.1, §9.0).
 *
 * <p>Responsibility: qualifies {@code Profile.idNo}. The pair {@code (idType, idNo)} is the basis for
 * the <b>blind-index dedup hash</b> enforcing one-person-one-account (D15) and drives trust-tier
 * progression to T3.</p>
 *
 * <p>WHY {@link #VOTER} is special: the voter ID ties a person to a constituency and is
 * <b>authoritative for the electoral location</b> (D13). Verifying with a voter ID sets the
 * {@code isElectoral} location authoritatively; a {@link #NATIONAL} (NIDA) ID establishes identity but
 * not, by itself, the electoral pin (PRD §9.0).</p>
 */
public enum IdType {

    /** NIDA national ID — authoritative for identity (NIDA is the national ID authority). */
    NATIONAL,

    /** Voter ID — authoritative for the electoral location/constituency (D13). */
    VOTER,

    /** Passport — accepted for identity (e.g. diaspora) where national/voter ID is unavailable. */
    PASSPORT
}
