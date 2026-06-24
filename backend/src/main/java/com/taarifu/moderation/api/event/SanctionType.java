package com.taarifu.moderation.api.event;

/**
 * The kind of account sanction a moderation takedown recommends against the content author's account
 * (PRD §18, US-12.2, UC-H02; ADR-0013 §2 "moderation takedown → owner effect is event-driven").
 *
 * <p>Responsibility: the closed, cross-module vocabulary the moderation module publishes on
 * {@link ModerationSanctionApplied} so the <b>identity</b> module can apply the corresponding account-state
 * change <b>asynchronously</b> — without moderation ever importing identity, and without identity importing
 * moderation's {@code domain} (ARCHITECTURE.md §3.2; ADR-0013 §2/§3). It deliberately lives in
 * {@code moderation.api.event} (the module's public contract) so a sibling consumer may import it across the
 * boundary, and it mirrors only the <b>sanctioning</b> subset of
 * {@link com.taarifu.moderation.domain.model.enums.ModerationActionType} — the non-sanctioning actions
 * (APPROVE/HIDE/REMOVE/WARN) act on the <i>content</i>, not the account, and never raise this event.</p>
 *
 * <p>WHY a dedicated api-package enum (not the domain {@code ModerationActionType}): the domain enum carries
 * content-level outcomes (HIDE/REMOVE) that are meaningless to identity and would leak a moderation domain
 * type across the boundary. Exposing a small, account-scoped enum keeps the contract minimal (ISP) and the
 * identity consumer dependent only on {@code moderation.api.event} (ADR-0013 §3).</p>
 */
public enum SanctionType {

    /**
     * Suspend the author's account so it can no longer authenticate/act until reinstated (recoverable —
     * the identity module owns the actual {@code UserStatus.SUSPENDED} transition; M14, US-14.1).
     */
    SUSPEND,

    /**
     * Require the author to (re)verify their identity/claim before they may continue (the identity module
     * owns the gating flag). Distinct from {@link #SUSPEND}: the account is not suspended, only fenced until
     * verification.
     */
    VERIFY_REQUEST
}
