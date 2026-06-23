package com.taarifu.responders.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request DTO to assign a report to a responder and drive it to {@code ASSIGNED} (D21, §12.1).
 *
 * <p>Responsibility: validated input for the responder-side "take this case" action. The report id is in
 * the path; this body carries the responder taking the case. The actual lifecycle transition runs through
 * reporting's published {@code ReportLifecycleApi} command port (the {@code responders → reporting} edge,
 * ADR-0013 §4a).</p>
 *
 * @param responderId the responder (owner) taking the case (required, public id).
 */
public record AssignCaseRequest(
        @NotNull UUID responderId
) {
}
