package com.taarifu.accountability.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public response DTO for a representative's right-of-reply to a rating
 * (PRD §10 Epic M6, US-6.2; the D-rated-fairness rule).
 *
 * <p>The reply is shown <i>with</i> the rating it answers so the public record is never one-sided. Exposes
 * the representative subject, the reply text, and whether it was posted on the representative's behalf by a
 * curator — public ids only, no PII (the author account is not surfaced publicly).</p>
 *
 * @param id               the reply's public id.
 * @param representativeId the rated representative's public id (the reply's subject).
 * @param onBehalf         {@code true} if a curator posted it on the representative's behalf (D-Q4).
 * @param body             the reply text.
 * @param repliedAt        when the reply was first posted (creation time).
 */
public record RatingReplyDto(
        UUID id,
        UUID representativeId,
        boolean onBehalf,
        String body,
        Instant repliedAt
) {
}
