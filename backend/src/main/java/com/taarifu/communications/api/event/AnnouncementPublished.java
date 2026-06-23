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
 * package lets other modules subscribe without importing this module's internals. TODO(wiring): once the
 * shared transactional-outbox base exists ({@code common.outbox}), this event is written to the outbox
 * in the same transaction as the publish and relayed to the bus; until then the publish service raises
 * it in-process for the dispatcher (ARCHITECTURE §8 MVP "even in-process ApplicationEventPublisher").</p>
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
}
