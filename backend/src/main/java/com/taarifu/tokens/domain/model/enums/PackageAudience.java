package com.taarifu.tokens.domain.model.enums;

/**
 * The buyer segment a {@link com.taarifu.tokens.domain.model.TokenPackage} targets
 * (PRD §23.4 — Phase 2 purchase seam).
 *
 * <p>Responsibility: lets the (Phase 2) catalogue offer citizen-sized small packs separately from larger
 * org/provider packs and subscriptions. Modelled now only to lock the purchase seam in the schema; no
 * real catalogue or payment flow ships in MVP (PRD §23.6).</p>
 */
public enum PackageAudience {

    /** Individual citizens — small packs. */
    CITIZEN,

    /** Organisations — larger packs / authoring bundles. */
    ORG,

    /** Service providers — B2B packs / subscriptions (§24). */
    PROVIDER
}
