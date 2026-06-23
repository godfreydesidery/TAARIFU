package com.taarifu.moderation.api.dto;

import com.taarifu.moderation.domain.model.enums.AppealStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to decide an open appeal (PRD §25.8, UC-H03).
 *
 * <p>Validated at the edge; the deciding moderator is taken from the security context, and the service
 * enforces appeal independence — the decider must be a <b>different</b> moderator than the one who took
 * the original action (§25.8, Appendix F footnote ᵉ). The {@code outcome} must be a terminal decision.</p>
 *
 * @param outcome      {@link AppealStatus#UPHELD} (action stands) or {@link AppealStatus#OVERTURNED}.
 * @param decisionNote the decision rationale (operator-facing; no PII/content).
 */
public record DecideAppealRequest(
        @NotNull(message = "moderation.appealOutcome.required") AppealStatus outcome,
        @Size(max = 1000, message = "moderation.decisionNote.tooLong") String decisionNote
) {
}
