package com.taarifu.communications.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request to follow a target (area / representative / category) — PRD §9.1, UC-G05, M4.
 *
 * <p>Responsibility: the validated boundary input for {@code POST /subscriptions}. The
 * {@code targetType} is the {@link com.taarifu.communications.domain.model.enums.SubscriptionTargetType}
 * name, validated in the service; the {@code targetId} is the followed entity's public id, resolved
 * through the owning module (geography/institutions/reporting).</p>
 *
 * @param targetType the kind of target (AREA / REPRESENTATIVE / CATEGORY), required.
 * @param targetId   the target's public id, required.
 */
public record FollowRequest(
        @NotBlank(message = "{communications.subscription.targetType.required}")
        String targetType,

        @NotNull(message = "{communications.subscription.targetId.required}")
        UUID targetId
) {
}
