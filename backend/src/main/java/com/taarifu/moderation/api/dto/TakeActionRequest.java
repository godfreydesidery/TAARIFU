package com.taarifu.moderation.api.dto;

import com.taarifu.moderation.domain.model.enums.ModerationActionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to take a moderation action on a queue item (PRD §18, US-12.2, UC-H02).
 *
 * <p>Validated at the edge; the acting moderator is taken from the security context (never the body). The
 * {@code reasonCode} is a machine reason for the immutable action log — never PII or copied content.</p>
 *
 * @param type       the decision to record (approve/hide/remove/warn/suspend/verify-request).
 * @param reasonCode machine reason for the decision (e.g. {@code RULE_HARASSMENT}); required, no PII.
 * @param note       optional operator-facing note (no content/PII).
 */
public record TakeActionRequest(
        @NotNull(message = "moderation.actionType.required") ModerationActionType type,
        @NotBlank(message = "moderation.reasonCode.required")
        @Size(max = 64, message = "moderation.reasonCode.tooLong") String reasonCode,
        @Size(max = 1000, message = "moderation.note.tooLong") String note
) {
}
