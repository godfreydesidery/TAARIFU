package com.taarifu.accountability.domain.port;

import java.util.UUID;

/**
 * The accountability module's port for the <b>right-of-reply ownership fence</b>: "is this account the
 * representative behind this rating's subject?" (PRD &sect;10 Epic M6, US-6.2; the D-rated-fairness
 * conflict-of-interest rule; ARCHITECTURE &sect;3.2 cross-module via ports; ADR-0013).
 *
 * <p>Responsibility: answers the single question {@code RatingReplyService}'s ownership check needs — may the
 * authenticated account post the representative's right-of-reply to a given rating? A representative may reply
 * <b>only</b> to a rating about <i>themselves</i>; they must never be able to reply to (and thereby colour) a
 * rating about a rival. That requires resolving the link between an account and the representative it speaks
 * for — a fact owned by the <b>institutions</b>/<b>identity</b> modules, which accountability must not reach
 * into. The service depends on this <b>abstraction</b>, not on those modules directly (SOLID/DIP; the
 * {@code WardResolver}/{@code Geocoder} cross-module-injectable-port pattern, ARCHITECTURE &sect;7).</p>
 *
 * <h3>Wired adapter + deny fail-safe</h3>
 * <p>The accountability module ships the <b>real</b> adapter
 * ({@code InstitutionsBackedOwnershipAdapter}) as the live default — it resolves ownership by delegating to
 * institutions' published {@code RepresentativeQueryApi.ownsRepresentative} (which bridges account → profile via
 * identity, then matches the representative's linked {@code profileId} — §6.4). So the self-reply path is
 * genuinely available: a rated representative can reply to a rating about <i>themselves</i>, and the same
 * resolver returns {@code false} for a rating about a rival, holding the fence. A <b>deny-stub</b>
 * ({@code DenyByDefaultOwnershipAdapter}) remains as the fail-safe backstop, registered only via
 * {@code @ConditionalOnMissingBean} — if the real adapter were ever absent it answers {@code false} for every
 * account, so the worst case is "self-reply temporarily unavailable", never "anyone can speak as a
 * representative" (deny-by-default, CLAUDE.md &sect;3 "fail safe"). The right-of-reply feature is additionally
 * usable via the <b>curated on-behalf</b> path ({@code ADMIN}/{@code ROOT}), which does not consult this port.</p>
 */
public interface RepresentativeOwnershipPort {

    /**
     * Answers whether {@code accountPublicId} is the linked account of representative
     * {@code representativePublicId} — i.e. whether that account may post the representative's own
     * right-of-reply.
     *
     * <p>Read-only and total: returns {@code false} (never throws) for a {@code null} input, an account that
     * is not the representative's, or a representative with no linked account — the caller turns {@code false}
     * into a localised conflict-of-interest rejection. The default stub returns {@code false} for everything.</p>
     *
     * @param accountPublicId        the authenticated account's public id (from the security context).
     * @param representativePublicId the rated representative's public id (the rating's subject).
     * @return {@code true} only if the account is the representative's own linked account.
     */
    boolean isLinkedAccountOf(UUID accountPublicId, UUID representativePublicId);
}
