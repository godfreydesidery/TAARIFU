package com.taarifu.responders.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for a {@link com.taarifu.responders.domain.model.Responder} capability (PRD §24.1).
 *
 * <p>Responsibility: the boundary shape for the public "who handles what" directory and admin views.
 * It surfaces the handled category ids and (for area-scoped responders) coverage area ids so a client
 * can show "this provider handles X in areas Y" without a second call. Ids are public ids of the
 * reporting-category / geography-area entities this module references by id (no entity leak).</p>
 *
 * @param id                 the responder's public id (UUID).
 * @param organisationId     the owning organisation's public id.
 * @param organisationName   the owning organisation's display name (denormalised for directory display).
 * @param name               capability display name.
 * @param responderType      routing kind/sector (e.g. {@code UTILITY}).
 * @param status             availability status (e.g. {@code ACTIVE}).
 * @param coverageType       {@code AREAS} or {@code NATIONWIDE}.
 * @param handledCategoryIds reporting-category public ids this responder handles.
 * @param coverageAreaIds    geography area public ids covered (empty when nationwide).
 * @param slaPolicy          SLA policy text, or {@code null}.
 */
public record ResponderDto(
        UUID id,
        UUID organisationId,
        String organisationName,
        String name,
        String responderType,
        String status,
        String coverageType,
        List<UUID> handledCategoryIds,
        List<UUID> coverageAreaIds,
        String slaPolicy
) {
}
