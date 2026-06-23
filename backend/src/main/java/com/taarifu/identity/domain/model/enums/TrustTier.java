package com.taarifu.identity.domain.model.enums;

/**
 * Citizen trust tier from tiered verification (PRD §7.3 [LOCKED], D13).
 *
 * <p>Responsibility: encodes how strongly an account's identity is established, which gates what civic
 * actions it may take. The tier on a JWT is only a hint; high-stakes actions <b>re-check the live tier
 * server-side</b> (PRD §17, §18). The integrity fence pairs tier with electoral scope and
 * one-per-person — and <b>never</b> a token balance (D18, §23.5).</p>
 *
 * <p>Progression (PRD §7.3): {@code T0} guest → {@code T1} phone/OTP verified → {@code T2} complete
 * profile (email/phone verified) → {@code T3} national/voter-ID verified. Binding actions (sign a
 * constituency petition, rate an MP, vote in a binding poll) require {@code T3} on the electoral
 * location (D13).</p>
 */
public enum TrustTier {

    /** Unverified guest; browse/read only. */
    T0,

    /** Phone/OTP verified; basic registered citizen. */
    T1,

    /** Complete, verified profile (email + phone); can file reports. */
    T2,

    /** National/voter-ID verified; eligible for binding civic actions (electoral location). */
    T3
}
