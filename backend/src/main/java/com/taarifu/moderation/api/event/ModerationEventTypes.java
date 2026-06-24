package com.taarifu.moderation.api.event;

/**
 * The moderation module's published outbox <b>taxonomy keys</b> — the {@code eventType} /
 * {@code aggregateType} string constants stamped onto an {@code EventEnvelope} for moderation-sourced
 * domain events (ADR-0013 §2 "moderation takedown → owner effect is event-driven"; ADR-0014 §1/§4).
 *
 * <p>Responsibility: one source of truth for the moderation event taxonomy so the producer (the moderation
 * queue service) and the consumer (the identity module's sanction handler) reference the <b>same</b> literal
 * — never two drifting copies of {@code "MODERATION_SANCTION_APPLIED"} (DRY; CLAUDE.md §3). It lives in
 * {@code moderation.api.event} (the module's public contract) so a sibling may import the constants across
 * the boundary — a sanctioned cross-module {@code ..api..} reference (ADR-0013 §3), not a reach into
 * moderation's internals.</p>
 */
public final class ModerationEventTypes {

    private ModerationEventTypes() {
        // Constants holder — not instantiable.
    }

    /**
     * The {@code aggregateType} for moderation-action-sourced events: the producing aggregate is the
     * {@code ModerationItem} whose action recommended the sanction (ADR-0014 §1 — the aggregate type is for
     * routing/replay/diagnostics, never an FK).
     */
    public static final String AGGREGATE_MODERATION_ITEM = "MODERATION_ITEM";

    /**
     * The {@code eventType} taxonomy key for "a moderation action sanctioned a user's account"
     * (SUSPEND / VERIFY_REQUEST; PRD §18, UC-H02). Emitted by the moderation queue service inside the
     * take-action transaction; the <b>identity</b> module's handler consumes it asynchronously off the
     * outbox relay and applies the account-state change ({@code suspend()} / verify-request gate) —
     * moderation never reaches into identity (ARCHITECTURE.md §3.2; ADR-0013 §2).
     *
     * <p>The event's idempotency key is the outbox row's {@code public_id} (carried as
     * {@code EventEnvelope.eventId} on dispatch); the identity handler must be idempotent on it (the
     * account-state transition is naturally idempotent — re-suspending an already-suspended account is a
     * no-op — ADR-0014 §3).</p>
     */
    public static final String MODERATION_SANCTION_APPLIED = "MODERATION_SANCTION_APPLIED";
}
