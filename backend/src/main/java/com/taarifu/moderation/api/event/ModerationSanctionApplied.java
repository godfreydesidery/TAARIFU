package com.taarifu.moderation.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published domain event: a moderation action recommended an account-level sanction against a content
 * author (PRD §18, US-12.2, UC-H02; ADR-0013 §2 "moderation takedown → owner effect is event-driven";
 * ADR-0014 §1/§4).
 *
 * <p>Responsibility: the immutable, cross-module <b>async</b> contract moderation emits — on the
 * {@code outboxWriter.append(...)} inside the take-action transaction — when a moderator records a
 * {@code SUSPEND} or {@code VERIFY_REQUEST} action. The <b>identity</b> module's handler consumes it
 * <i>asynchronously</i> off the outbox relay and applies the account-state change
 * ({@code User.suspend()} / a verify-request gate). WHY async (not a synchronous call into identity): a
 * synchronous {@code moderation → identity} write would couple the moderator's request to identity's
 * availability and breach the dependency rule; emitting an event keeps the action transaction fast and the
 * modules decoupled (ARCHITECTURE.md §3.2/§8; ADR-0013 §2). Moderation never imports identity.</p>
 *
 * <p><b>🔒 ids/enums ONLY — never PII</b> (PRD §18, PDPA, ADR-0014 §1): the payload carries the author's
 * opaque <b>account</b> public id and the controlled-vocabulary {@link SanctionType}, and nothing else — no
 * name, no phone, no national/voter ID, no content body, no moderator note. The identity handler re-reads
 * anything more it needs by {@link #subjectAccountId} through its own model; it never expects identity on
 * this envelope.</p>
 *
 * <p>The taxonomy key handlers register on is {@link ModerationEventTypes#MODERATION_SANCTION_APPLIED}; the
 * aggregate type is {@link ModerationEventTypes#AGGREGATE_MODERATION_ITEM}. The event's idempotency key is
 * the outbox row's {@code public_id} (carried as {@code EventEnvelope.eventId} on dispatch); the identity
 * handler is idempotent on it — the account-state transition is naturally idempotent (re-suspending an
 * already-suspended account is a no-op — ADR-0014 §3).</p>
 *
 * @param subjectAccountId the sanctioned author's <b>account</b> public id ({@code app_user.publicId}, the
 *                         same grain as the JWT subject); the identity handler resolves the account by this
 *                         id. Never a profile/display id, never PII.
 * @param sanctionType     the kind of sanction to apply ({@code SUSPEND} / {@code VERIFY_REQUEST}); the
 *                         account-scoped subset of the moderation action taken.
 * @param occurredAt       domain-time the sanctioning action was taken (UTC).
 */
public record ModerationSanctionApplied(
        UUID subjectAccountId,
        SanctionType sanctionType,
        Instant occurredAt
) {
}
