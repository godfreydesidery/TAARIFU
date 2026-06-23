package com.taarifu.responders.api.dto;

import com.taarifu.responders.domain.model.enums.CoverageType;
import com.taarifu.responders.domain.model.enums.ResponderStatus;
import com.taarifu.responders.domain.model.enums.ResponderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO to update a responder capability (admin CRUD, PRD §24.1).
 *
 * <p>Responsibility: validated input for {@code PUT /responders/{id}}. Replaces the capability's
 * mutable configuration — name, type, status, coverage, handled categories and SLA. The owning
 * organisation is immutable here (a capability does not move between organisations; delete and
 * recreate instead, preserving assignment history).</p>
 *
 * @param name               capability display name (required).
 * @param responderType      routing kind/sector (required).
 * @param status             availability status (required).
 * @param coverageType       AREAS or NATIONWIDE (required).
 * @param handledCategoryIds reporting-category ids handled (replaces the existing set).
 * @param coverageAreaIds    geography area ids covered (replaces the existing set).
 * @param slaPolicy          SLA policy text (optional).
 */
public record UpdateResponderRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull ResponderType responderType,
        @NotNull ResponderStatus status,
        @NotNull CoverageType coverageType,
        List<UUID> handledCategoryIds,
        List<UUID> coverageAreaIds,
        @Size(max = 1000) String slaPolicy
) {
}
