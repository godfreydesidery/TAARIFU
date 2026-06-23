package com.taarifu.moderation.api.dto;

import com.taarifu.moderation.domain.model.ModerationAction;
import com.taarifu.moderation.domain.model.enums.ModerationActionType;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for an append-only {@link ModerationAction} (PRD §18, US-12.2, UC-H02).
 *
 * <p>Moderator-facing; returned when an action is taken (so the moderator gets the action's public id to
 * track an appeal) and when listing an item's action history. Carries the machine reason code, never PII.</p>
 *
 * @param id         the action's public id.
 * @param itemId     the resolved queue item's public id.
 * @param type       the decision taken.
 * @param reasonCode the machine reason for the decision.
 * @param takenAt    when the action was taken (UTC).
 */
public record ModerationActionDto(
        UUID id,
        UUID itemId,
        ModerationActionType type,
        String reasonCode,
        Instant takenAt
) {

    /**
     * Maps an entity to its read model.
     *
     * @param action the persisted action.
     * @return the DTO.
     */
    public static ModerationActionDto from(ModerationAction action) {
        return new ModerationActionDto(action.getPublicId(), action.getItem().getPublicId(),
                action.getType(), action.getReasonCode(), action.getTakenAt());
    }
}
