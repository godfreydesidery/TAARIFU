package com.taarifu.moderation.api.dto;

import com.taarifu.moderation.domain.model.Appeal;
import com.taarifu.moderation.domain.model.enums.AppealStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for an {@link Appeal} (PRD §25.8, UC-H03).
 *
 * <p>Returned to the appellant (their own appeal) and to moderators (the appeal queue). Carries the
 * appealed action's public id, the status, and — once decided — the outcome and decision instant.</p>
 *
 * @param id           the appeal's public id.
 * @param actionId     the appealed action's public id.
 * @param status       the appeal lifecycle state / outcome.
 * @param decidedAt    when the appeal was decided (UTC), or {@code null} while OPEN.
 * @param createdAt    when the appeal was filed (UTC).
 */
public record AppealDto(
        UUID id,
        UUID actionId,
        AppealStatus status,
        Instant decidedAt,
        Instant createdAt
) {

    /**
     * Maps an entity to its read model.
     *
     * @param appeal the persisted appeal.
     * @return the DTO.
     */
    public static AppealDto from(Appeal appeal) {
        return new AppealDto(appeal.getPublicId(), appeal.getAction().getPublicId(),
                appeal.getStatus(), appeal.getDecidedAt(), appeal.getCreatedAt());
    }
}
