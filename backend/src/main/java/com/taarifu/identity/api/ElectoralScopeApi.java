package com.taarifu.identity.api;

import java.util.UUID;

/**
 * The identity module's <b>public, in-process query port</b> for resolving a citizen's binding electoral
 * scope (ADR-0013 §1; D13). A sibling module that gates a binding democratic action on electoral scope
 * (engagement petition-sign, accountability rate-representative) depends on, and calls, this interface —
 * never identity's {@code domain}/{@code infrastructure} (ARCHITECTURE §3.2).
 *
 * <p>Responsibility: answer the single question "is this user an elector of that constituency?", resolved
 * from the user's <b>single voter-ID-authoritative {@code isElectoral} {@code ProfileLocation}</b> (PRD
 * §9.0, D13) and the constituency it maps to. Identity owns this because identity owns the profile's
 * electoral location; the caller treats the boolean as opaque truth and does not reach past it.</p>
 *
 * <p><b>🔒 Civic-integrity fence (D18, PRD §23.5):</b> this port reads only the actor's electoral location
 * and the target constituency — there is deliberately <b>no</b> token/balance input or output here. It is
 * the electoral-scope half of the binding-action fence (tier + electoral scope + one-per-person), and it
 * must never be on a path that consults a wallet. A user with no resolved electoral constituency is
 * <b>not</b> an elector of anywhere (deny-by-default).</p>
 */
public interface ElectoralScopeApi {

    /**
     * Resolves whether the given user is an elector of the given constituency.
     *
     * <p>Resolution: the user's account → its identity {@code Profile} → the profile's single
     * {@code isElectoral} {@code ProfileLocation} → that location's derived constituency. Returns
     * {@code true} iff that constituency's public id equals {@code constituencyPublicId}. Any missing link
     * (no profile, no electoral location, no derived constituency) yields {@code false} (deny-by-default —
     * a binding action is denied {@code OUT_OF_SCOPE} by the caller).</p>
     *
     * @param userPublicId         the actor's immutable account public id (the JWT subject — never a
     *                             body-supplied id), the grain {@code CurrentUser.requirePublicId()} returns.
     * @param constituencyPublicId the target constituency's public id (e.g. the rep's constituency or the
     *                             petition's constituency scope); {@code null} is never an elector match.
     * @return {@code true} if the user's authoritative electoral location is in that constituency.
     */
    boolean isElectorOf(UUID userPublicId, UUID constituencyPublicId);

    /**
     * Resolves whether the given user is an elector of the given <b>ward</b> (Kata) — the ward-tier half of
     * the binding-action electoral gate (F1, D13).
     *
     * <p>WHY this exists alongside {@link #isElectorOf} (F1): a Councillor (Diwani) holds a ward, not a
     * constituency, so a citizen rating/petitioning a councillor must be an elector of that councillor's
     * ward — exactly the ward of their single voter-ID-authoritative {@code isElectoral}
     * {@code ProfileLocation} (the minimum pin granularity, PRD §9.0). Gating MPs by constituency but
     * leaving councillors ungated let anyone in Tanzania rate/petition any councillor; this closes that gap
     * while keeping the constituency path for MPs unchanged.</p>
     *
     * <p>Resolution: the user's account → its identity {@code Profile} → the profile's single
     * {@code isElectoral} {@code ProfileLocation} → that location's ward. Returns {@code true} iff that
     * ward's public id equals {@code wardPublicId}. Any missing link (no profile, no electoral location)
     * yields {@code false} (deny-by-default — the caller denies {@code OUT_OF_SCOPE}). The same fence holds:
     * no token/balance is read on this path (D18, §23.5).</p>
     *
     * @param userPublicId  the actor's immutable account public id (the JWT subject — never a body id).
     * @param wardPublicId  the target ward's public id (the ward-tier rep's ward); {@code null} is never a match.
     * @return {@code true} if the user's authoritative electoral location is in that ward.
     */
    boolean isElectorOfWard(UUID userPublicId, UUID wardPublicId);
}
