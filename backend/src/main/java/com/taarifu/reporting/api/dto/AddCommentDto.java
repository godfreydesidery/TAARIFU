package com.taarifu.reporting.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the reporter to add information/a comment to their report (PRD §10 US-3.2 "ability to
 * add info/comment").
 *
 * <p>Responsibility: the validated boundary input for a citizen comment. A reporter comment is always a
 * <b>public</b> timeline event (the citizen cannot post an internal note); if the case was
 * {@code AWAITING_INFO}, the comment is treated as the reply that resumes work (→ {@code IN_PROGRESS}),
 * which the service decides (US-3.4 reply flow).</p>
 *
 * @param message the comment/info text (required, ≤ 4000).
 */
public record AddCommentDto(
        @NotBlank @Size(max = 4000) String message
) {
}
