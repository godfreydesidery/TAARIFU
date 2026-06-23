package com.taarifu.responders.api.dto;

import com.taarifu.responders.domain.model.enums.CoverageType;
import com.taarifu.responders.domain.model.enums.ResponderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO to create a responder capability under an organisation (admin CRUD, PRD §24.1).
 *
 * <p>Responsibility: validated input for {@code POST /organisations/{organisationId}/responders}. The
 * handled-category and coverage-area ids are public ids of reporting/geography entities referenced by
 * id (this module does not import those modules; validation that the ids exist is a later wiring step).
 * // TODO(wiring): validate category/area ids against reporting/geography APIs.</p>
 *
 * @param name               capability display name (required).
 * @param responderType      routing kind/sector (required).
 * @param coverageType       AREAS or NATIONWIDE (required).
 * @param handledCategoryIds reporting-category ids handled (optional; may be empty initially).
 * @param coverageAreaIds    geography area ids covered (optional; ignored when nationwide).
 * @param slaPolicy          SLA policy text (optional).
 */
public record CreateResponderRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull ResponderType responderType,
        @NotNull CoverageType coverageType,
        List<UUID> handledCategoryIds,
        List<UUID> coverageAreaIds,
        @Size(max = 1000) String slaPolicy
) {
}
