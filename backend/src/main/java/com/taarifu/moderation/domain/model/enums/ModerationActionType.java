package com.taarifu.moderation.domain.model.enums;

/**
 * The decision a moderator records on a queue item (PRD §18, US-12.2, UC-H02; Appendix
 * {@code moderation_action_taken.action}).
 *
 * <p>Responsibility: the closed set of takedown/moderation outcomes. Each value is an append-only
 * {@link com.taarifu.moderation.domain.model.ModerationAction} row (never updated/deleted) so the full
 * decision history of a subject is reconstructable and auditable (§18, §25.8). The values mirror the
 * analytics contract exactly ({@code APPROVE/HIDE/REMOVE/WARN/SUSPEND/VERIFY}).</p>
 *
 * <p>WHY the action against the <i>content</i> is decoupled from any account sanction: {@link #REMOVE}
 * hides the content; {@link #SUSPEND} sanctions the <i>author's account</i> — the actual account-state
 * change is owned by the identity module, never done by reaching into identity from here
 * (ARCHITECTURE.md §3.2). This is now <b>wired</b> asynchronously: {@code ModerationQueueService.takeAction}
 * appends a {@code MODERATION_SANCTION_APPLIED} event (carrying only the author's account public id + the
 * {@code SanctionType}) to the transactional outbox, and identity's {@code ModerationSanctionHandler}
 * consumes it off the relay and applies the account-state change (ADR-0013 §2; ADR-0014 §1/§4).</p>
 */
public enum ModerationActionType {

    /** No violation — the content stands (clears any hold). */
    APPROVE,

    /** Hide the content from public view (reversible). */
    HIDE,

    /** Remove the content (takedown). */
    REMOVE,

    /** Warn the author (notification; content may stand). */
    WARN,

    /** Recommend suspension of the author's account (identity module applies the sanction). */
    SUSPEND,

    /** Require identity/claim verification of the author before they may continue. */
    VERIFY_REQUEST
}
