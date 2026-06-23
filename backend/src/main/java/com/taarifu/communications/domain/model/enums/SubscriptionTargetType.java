package com.taarifu.communications.domain.model.enums;

/**
 * What a citizen's {@link com.taarifu.communications.domain.model.Subscription} follows
 * (PRD §9.1 "Follow: Profile *-* (Area|Representative|Category|Project)", M4).
 *
 * <p>Responsibility: the discriminator for a follow edge. A subscription pairs a follower profile id
 * with a target id whose <i>kind</i> is one of these. The target is referenced by its public
 * {@code UUID} only — never a cross-module FK — because the targets are owned by other modules
 * (geography areas, institutions representatives, reporting categories) and this module must not reach
 * into their tables (ARCHITECTURE §3.2; the parallel-build isolation rule).</p>
 *
 * <p>This increment models the MVP follow targets the feed needs: {@code AREA} (a geography location,
 * e.g. a Ward), {@code REPRESENTATIVE} (an MP/Councillor), and {@code CATEGORY} (an issue category).
 * {@code PROJECT} and {@code PETITION} follow edges arrive with the Phase-2 modules that own them
 * (KISS — add the discriminator value when that module ships).</p>
 */
public enum SubscriptionTargetType {

    /** A geography area (e.g. a Ward/Kata) — drives "announcements in my area" feed inclusion. */
    AREA,

    /** An elected representative (MP/Mbunge, Councillor/Diwani) — their announcements reach followers. */
    REPRESENTATIVE,

    /** An issue category (e.g. Water) — announcements tagged with it reach interested followers. */
    CATEGORY
}
