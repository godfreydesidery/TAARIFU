package com.taarifu.identity.domain.model.enums;

/**
 * How a profile relates to a pinned place on a {@code ProfileLocation} (PRD §9.0, §9.1, D12).
 *
 * <p>Responsibility: types each of a profile's many locations so the platform can reason about context
 * (e.g. <i>home/ancestral</i> = Rombo vs <i>residence</i> = Dar). Association type is independent of
 * the two singleton flags {@code isPrimary} (default context) and {@code isElectoral} (binding civic
 * weight) — a profile may have several associations but exactly one primary and one electoral
 * (PRD §9.0, D12).</p>
 */
public enum AssociationType {

    /** Ancestral / family-origin home (e.g. the rural ward a citizen "comes from"). */
    HOME_ANCESTRAL,

    /** Current place of residence. */
    RESIDENCE,

    /** Place of work. */
    WORK,

    /** A family connection (relatives' location). */
    FAMILY,

    /** A business interest location. */
    BUSINESS,

    /** Owned property location. */
    PROPERTY,

    /** A general interest (a place the citizen follows but does not live/work in). */
    INTEREST
}
