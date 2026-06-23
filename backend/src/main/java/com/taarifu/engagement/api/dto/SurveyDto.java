package com.taarifu.engagement.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a {@link com.taarifu.engagement.domain.model.Survey} (PRD §9.1, §12.2 M8).
 *
 * <p>Responsibility: the boundary shape for survey/poll reads. The {@code questions}/{@code audienceScope}
 * are the raw JSON blobs (Phase-2 scaffold). Only the public id is exposed (ADR-0006).</p>
 *
 * @param id             the survey's public id.
 * @param title          title.
 * @param description    description, or {@code null}.
 * @param type           SURVEY or POLL.
 * @param binding        whether responding is a binding (T3 + one-per-person) act.
 * @param audienceScope  JSON audience descriptor, or {@code null} for open-to-all.
 * @param questions      JSON questions definition, or {@code null}.
 * @param startsAt       open instant, or {@code null}.
 * @param endsAt         close instant, or {@code null}.
 * @param anonymous      whether responses are anonymous.
 * @param status         lifecycle state.
 * @param creatorProfileId authoring person's profile public id, or {@code null}.
 * @param creatorOrgId   authoring organisation public id, or {@code null}.
 */
public record SurveyDto(
        UUID id,
        String title,
        String description,
        String type,
        boolean binding,
        String audienceScope,
        String questions,
        Instant startsAt,
        Instant endsAt,
        boolean anonymous,
        String status,
        UUID creatorProfileId,
        UUID creatorOrgId
) {
}
