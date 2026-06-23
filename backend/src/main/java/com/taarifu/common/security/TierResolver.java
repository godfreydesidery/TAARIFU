package com.taarifu.common.security;

import java.util.UUID;

/**
 * The live trust-tier resolution port — the keystone of MF-2 (AUTH-DESIGN §7, ADR-0011 §3).
 *
 * <p>Responsibility: computes a caller's <b>current</b> trust tier (T0–T3) from <b>live database state</b>
 * for a given account {@code publicId}. This is the <b>only</b> authority for {@code @RequiresTier}
 * gating — the {@code trustTier} JWT claim is a UI hint and is <b>never</b> an authorization input. The
 * interface lives in the shared kernel so every module's authz can depend on it; the implementation
 * (which queries identity tables) lives in {@code identity} and is wired as this bean (AUTH-DESIGN §2).</p>
 *
 * <p>WHY a port here, implementation in identity: keeps {@code common} dependency-free (it owns the
 * contract) while the live query stays in the owning module (no cross-module entity leak). A forged or
 * stale token tier cannot escalate because the resolver recomputes from the DB on every gated request.</p>
 */
public interface TierResolver {

    /**
     * Resolves the account's current trust tier from live DB state.
     *
     * @param userPublicId the account's immutable public id (the JWT subject).
     * @return the integer tier rank 0–3 (T0–T3), evaluated highest-predicate-first (AUTH-DESIGN §7.1);
     *         a deleted/suspended/unknown account resolves to {@code 0} (T0) — deny-by-default.
     */
    int resolveLiveTierRank(UUID userPublicId);
}
