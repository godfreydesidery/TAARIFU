package com.taarifu.moderation.api.dto;

import com.taarifu.moderation.domain.model.ModerationItem;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.domain.model.enums.ModerationItemStatus;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a {@link ModerationItem} (queue entry) shown to moderators (PRD §18, US-12.2, UC-H01).
 *
 * <p>Moderator-facing only (gated by {@code ROLE_MODERATOR}); never published. Carries the queue-ordering
 * signals (severity, flag count, SLA deadline) and the subject reference so a moderator can open the
 * content. The subject <i>author</i> is intentionally omitted from the wire model — it is a private
 * cross-module reference used server-side for the conflict-of-interest check, not for display.</p>
 *
 * @param id             the item's public id.
 * @param subjectType    the kind of content under review.
 * @param subjectId      the content's public id.
 * @param severity       triage severity (sets the SLA, orders the queue).
 * @param status         queue lifecycle state.
 * @param flagCount      number of distinct flaggers.
 * @param assignedTo     the reviewing moderator's public id, or {@code null} while PENDING.
 * @param slaDueAt       the §25.8 review-SLA deadline (UTC).
 * @param createdAt      when the item was opened (UTC).
 */
public record ModerationItemDto(
        UUID id,
        FlagSubjectType subjectType,
        UUID subjectId,
        ModerationSeverity severity,
        ModerationItemStatus status,
        int flagCount,
        UUID assignedTo,
        Instant slaDueAt,
        Instant createdAt
) {

    /**
     * Maps an entity to its read model.
     *
     * @param item the persisted queue item.
     * @return the DTO.
     */
    public static ModerationItemDto from(ModerationItem item) {
        return new ModerationItemDto(item.getPublicId(), item.getSubjectType(), item.getSubjectId(),
                item.getSeverity(), item.getStatus(), item.getFlagCount(), item.getAssignedModeratorId(),
                item.getSlaDueAt(), item.getCreatedAt());
    }
}
