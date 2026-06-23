package com.taarifu.responders.api.dto;

import com.taarifu.responders.domain.model.enums.AssignmentRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request DTO to assign a responder to a report (admin/owner action, PRD §24.3).
 *
 * <p>Responsibility: validated input for {@code POST /reports/{reportId}/responder-assignments}. The
 * report id comes from the path; this body carries the responder and the role. The single-OWNER
 * invariant (§24.3) is enforced by the service + a DB partial-unique index — attempting to add a second
 * OWNER yields a typed {@code CONFLICT}. The report is referenced by id, and its existence is validated
 * via reporting's published {@code ReportQueryApi} in the service (ADR-0013 §4a).</p>
 *
 * @param responderId the responder to assign (required, public id).
 * @param role        OWNER or COLLABORATOR (required).
 * @param slaPolicy   optional SLA snapshot text for this assignment.
 */
public record CreateAssignmentRequest(
        @NotNull UUID responderId,
        @NotNull AssignmentRole role,
        @Size(max = 1000) String slaPolicy
) {
}
