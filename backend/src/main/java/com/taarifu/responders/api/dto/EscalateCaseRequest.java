package com.taarifu.responders.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Request DTO for the responder-side escalate action (§12.1 — escalated stays active).
 *
 * <p>Responsibility: validated input carrying an optional escalation reason recorded on the case timeline.
 * The transition runs through reporting's {@code ReportLifecycleApi.escalate} (ADR-0013 §4a).</p>
 *
 * @param reason optional escalation reason for the timeline, or {@code null}.
 */
public record EscalateCaseRequest(
        @Size(max = 2000) String reason
) {
}
