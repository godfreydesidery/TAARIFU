package com.taarifu.moderation.api.dto;

import com.taarifu.moderation.domain.model.enums.FlagReason;
import com.taarifu.moderation.domain.model.enums.FlagSubjectType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to flag a piece of content (PRD §18, US-12.1, UC-E13/H01).
 *
 * <p>Validated at the edge; the flagger is taken from the security context (never from the body) so a
 * caller cannot flag <i>as</i> someone else. The flagged content is identified only by
 * {@code (subjectType, subjectId)} — the moderation module never imports the owning module.</p>
 *
 * @param subjectType the kind of content being flagged.
 * @param subjectId   the flagged content's public id (in its owning module).
 * @param reason      why it is being flagged.
 * @param detail      optional free-text detail (recommended/required-by-UI when {@code reason=OTHER});
 *                    must not contain copied content/PII — moderators open the subject to see it.
 */
public record FlagContentRequest(
        @NotNull(message = "moderation.subjectType.required") FlagSubjectType subjectType,
        @NotNull(message = "moderation.subjectId.required") UUID subjectId,
        @NotNull(message = "moderation.reason.required") FlagReason reason,
        @Size(max = 1000, message = "moderation.detail.tooLong") String detail
) {
}
