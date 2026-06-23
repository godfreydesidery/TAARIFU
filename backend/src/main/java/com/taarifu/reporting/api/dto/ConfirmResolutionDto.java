package com.taarifu.reporting.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the reporter to confirm or dispute a resolution (PRD §10 US-3.5, UC-D11/12/13).
 *
 * <p>Responsibility: the validated boundary input for the citizen's confirm/dispute decision on a
 * {@code RESOLVED} report. {@code confirmed = true} closes the case ({@code → CLOSED}); {@code false}
 * disputes it ({@code → REOPENED}), optionally carrying a reason the dispute timeline records.</p>
 *
 * @param confirmed {@code true} to confirm (close), {@code false} to dispute (reopen). Required.
 * @param reason    optional free-text reason, recorded on the timeline (especially useful for a dispute).
 */
public record ConfirmResolutionDto(
        @NotNull Boolean confirmed,
        @Size(max = 4000) String reason
) {
}
