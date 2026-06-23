package com.taarifu.institutions.domain.model.enums;

/**
 * How a {@link com.taarifu.institutions.domain.model.Representative} holds their seat — the basis of
 * their mandate (PRD §9.1, §22.6; D3, D17).
 *
 * <p>Responsibility: encodes whether the seat is tied to a geographic unit or not, which decides
 * whether the {@code constituency}/{@code ward} FKs are populated. WHY this exists: Tanzania has
 * non-constituency MPs — <b>special seats (Viti Maalum)</b> and <b>nominated</b> members — who have
 * <i>no</i> constituency at all. A naive "MP → constituency" FK (the legacy {@code Mp} anaemia) cannot
 * represent them; conflating "no constituency" with "missing data" corrupts both the directory and
 * "find my rep". This enum makes the absence <b>intentional and queryable</b> (PRD §9.1).</p>
 *
 * <p>Integrity rule (enforced in {@code Representative} + the migration's CHECK): a
 * {@link #CONSTITUENCY} mandate requires a constituency FK; {@link #COUNCILLOR_WARD} requires a ward FK;
 * {@link #SPECIAL_SEATS}/{@link #NOMINATED} require <b>both null</b>. This keeps the "one sitting MP per
 * constituency" invariant meaningful (it only applies to constituency-mandate MPs).</p>
 */
public enum Mandate {

    /** Elected to a specific constituency (Jimbo); the constituency FK is required. */
    CONSTITUENCY,

    /** Special seats (Viti Maalum) — no constituency; both geographic FKs are null. */
    SPECIAL_SEATS,

    /** Nominated/appointed member — no constituency; both geographic FKs are null. */
    NOMINATED,

    /** Councillor seat tied to a ward (Kata); the ward FK is required. */
    COUNCILLOR_WARD
}
