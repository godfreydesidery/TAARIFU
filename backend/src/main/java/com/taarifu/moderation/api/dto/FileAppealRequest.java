package com.taarifu.moderation.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to file an appeal against a moderation action (PRD §25.8, UC-H03).
 *
 * <p>Validated at the edge; the appellant is taken from the security context, and the service verifies
 * they are the actioned content's author before opening the appeal (only the affected party may appeal).
 * The appealed action is identified by the path id.</p>
 *
 * @param grounds the appellant's grounds for appeal (operator-facing free text; no copied content/PII).
 */
public record FileAppealRequest(
        @NotBlank(message = "moderation.grounds.required")
        @Size(max = 2000, message = "moderation.grounds.tooLong") String grounds
) {
}
