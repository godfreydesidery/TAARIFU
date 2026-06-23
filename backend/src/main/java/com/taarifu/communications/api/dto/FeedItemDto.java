package com.taarifu.communications.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A single item in a citizen's personalised feed (PRD §12 UC-G04, §22.6, M4).
 *
 * <p>Responsibility: the lean, read-optimised projection of an in-feed announcement. Deliberately
 * <b>smaller</b> than {@link AnnouncementDto} (no full audience set, no attachments) because the feed is
 * served to feature phones on tight data budgets (PRD §15) and is paginated — the client deep-links to
 * the full announcement by {@code id} when the citizen opens it. The body is the recipient's-locale
 * snippet the mapper selected (Swahili default — ADR-0010).</p>
 *
 * @param id        the source announcement's public id (deep-link target).
 * @param title     the headline.
 * @param snippet   a short body excerpt in the recipient's locale.
 * @param authorId  the authoring profile's public id.
 * @param publishedAt when it went live (UTC) — the feed's sort key (newest first).
 */
public record FeedItemDto(
        UUID id,
        String title,
        String snippet,
        UUID authorId,
        Instant publishedAt
) {
}
