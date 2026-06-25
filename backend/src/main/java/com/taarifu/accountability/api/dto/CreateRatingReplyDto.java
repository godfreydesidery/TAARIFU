package com.taarifu.accountability.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to post (or revise) a representative's right-of-reply to a rating
 * (PRD §10 Epic M6, US-6.2; the D-rated-fairness rule).
 *
 * <p>The rating being answered is identified by the path ({@code ratingId}); the replying account is taken
 * from the security context (never the body). A representative may reply only to a rating about themselves
 * (the ownership/conflict-of-interest fence, enforced server-side); an {@code ADMIN}/{@code ROOT} curator may
 * post the reply on the representative's behalf (D-Q4). At most one reply per rating — a reply, not a thread.</p>
 *
 * @param body the reply text (required).
 */
public record CreateRatingReplyDto(
        @NotBlank(message = "accountability.ratingReply.body.required")
        @Size(max = 2000, message = "accountability.ratingReply.body.tooLong") String body
) {
}
