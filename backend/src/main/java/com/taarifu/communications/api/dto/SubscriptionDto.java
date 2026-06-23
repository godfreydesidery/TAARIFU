package com.taarifu.communications.api.dto;

import java.util.UUID;

/**
 * Response DTO for a {@link com.taarifu.communications.domain.model.Subscription} follow edge
 * (PRD §9.1, UC-G05, M4).
 *
 * <p>Responsibility: the boundary shape for follow reads. Exposes the public {@code id} and the target
 * by type + public id (ADR-0006).</p>
 *
 * @param id         the subscription's public id (UUID) — the unfollow target.
 * @param targetType the kind of followed target.
 * @param targetId   the followed target's public id.
 */
public record SubscriptionDto(
        UUID id,
        String targetType,
        UUID targetId
) {
}
