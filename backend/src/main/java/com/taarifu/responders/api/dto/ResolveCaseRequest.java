package com.taarifu.responders.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the responder-side resolve action (US-3.4, §12.1).
 *
 * <p>Responsibility: validated input carrying the <b>required</b> resolution note. Reporting also re-checks
 * the note is non-blank when its {@code ReportLifecycleApi.resolve} runs (defence in depth), but validating
 * at the edge gives the agent field-level feedback.</p>
 *
 * @param resolutionNote the required resolution note (US-3.4).
 */
public record ResolveCaseRequest(
        @NotBlank(message = "reporting.report.resolutionRequired")
        @Size(max = 4000) String resolutionNote
) {
}
