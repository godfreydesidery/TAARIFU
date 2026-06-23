package com.taarifu.communications.api.event;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Published domain event: an announcement went live (ARCHITECTURE §8, PRD §16, M4/M5).
 *
 * <p>Responsibility: the immutable, cross-module contract emitted when an {@code Announcement}
 * transitions to {@code PUBLISHED}. It is the trigger for asynchronous <b>feed fan-out</b> and
 * <b>notification dispatch</b> (PRD §13/§16): subscribers' feeds are populated and per-channel
 * notifications are queued off the request thread so an announcement burst never blocks the author's
 * publish call (PRD §15, §28 R28). It carries only public {@code UUID}s and the audience descriptor —
 * never PII or the body text (consumers re-read the announcement by id).</p>
 *
 * <p>WHY an immutable record in {@code api.event} (not a Spring event class): events are the only async
 * cross-module contract (ARCHITECTURE §3.2/§8); keeping the record in the module's public {@code api}
 * package lets other modules subscribe without importing this module's internals. As of ADR-0014 this
 * record is the {@code EventEnvelope} payload: the publish application-service writes it to the shared
 * transactional outbox ({@code common.outbox.OutboxWriter}) <b>in the same transaction as the publish</b>,
 * and the relay later delivers it to {@code AnnouncementPublishedHandler} — there is no longer an
 * in-process {@code ApplicationEventPublisher} hop. The taxonomy key the dispatcher routes on is
 * {@link #EVENT_TYPE}; the producing aggregate type is {@link #AGGREGATE_TYPE}.</p>
 *
 * <p><b>🔒 ids/codes/enums only — never PII</b> (PRD §18): this record carries public {@code UUID}s, the
 * audience descriptor, and channel names; never the title/body or any author/recipient identity beyond the
 * opaque profile id. The handler re-reads any richer detail by id (ADR-0013).</p>
 *
 * @param announcementId   the published announcement's public id (consumers re-read by this id).
 * @param authorProfileId  the authoring profile's public id (for conflict-of-interest checks downstream).
 * @param audienceAreaIds  the targeted geo area public ids (fan-out matches followers by these).
 * @param categoryId       the tagged category public id, or {@code null}.
 * @param audienceRole     an optional role-name narrowing, or {@code null}.
 * @param channels         the channel names the author selected (FEED/PUSH/SMS/EMAIL).
 * @param publishedAt      when the announcement went live (UTC).
 */
public record AnnouncementPublished(
        UUID announcementId,
        UUID authorProfileId,
        Set<UUID> audienceAreaIds,
        UUID categoryId,
        String audienceRole,
        Set<String> channels,
        Instant publishedAt
) {

    /**
     * The outbox {@code eventType} taxonomy key for this event (ADR-0014 §5a). The producer stamps it on
     * the {@link com.taarifu.common.outbox.EventEnvelope} and the consuming {@code DomainEventHandler}
     * registers on it; sharing one constant keeps producer and handler in lock-step (DRY).
     */
    public static final String EVENT_TYPE = "ANNOUNCEMENT_PUBLISHED";

    /** The outbox {@code aggregateType} for the producing {@code Announcement} aggregate (ADR-0014 §1). */
    public static final String AGGREGATE_TYPE = "ANNOUNCEMENT";
}
