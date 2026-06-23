package com.taarifu.accountability.api.dto;

import com.taarifu.accountability.domain.model.enums.ContributionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request to create a curated {@link com.taarifu.accountability.domain.model.RepresentativeContribution}
 * (PRD §10 Epic M6, US-6.1; D-Q4 curated authorship).
 *
 * <p>Accepted only from an authorised author / {@code ROLE_ADMIN} (gated at the controller). Validation
 * messages are i18n keys resolved Swahili-first.</p>
 *
 * @param representativeId  the subject representative's public id (required).
 * @param type              the contribution kind (required).
 * @param title             short title (required).
 * @param summary           longer summary, or {@code null}.
 * @param occurredOn        the sitting date (required).
 * @param parliamentSession session/term reference, or {@code null}.
 * @param sourceUrl         provenance URL, or {@code null}.
 * @param attachmentRefs    object-store references, or {@code null}/empty.
 */
public record CreateContributionDto(
        @NotNull(message = "accountability.representative.required") UUID representativeId,
        @NotNull(message = "accountability.contribution.type.required") ContributionType type,
        @NotBlank(message = "accountability.contribution.title.required")
        @Size(max = 280, message = "accountability.contribution.title.tooLong") String title,
        @Size(max = 4000, message = "accountability.contribution.summary.tooLong") String summary,
        @NotNull(message = "accountability.contribution.date.required") LocalDate occurredOn,
        @Size(max = 120, message = "accountability.contribution.session.tooLong") String parliamentSession,
        @Size(max = 1000, message = "accountability.sourceUrl.tooLong") String sourceUrl,
        List<String> attachmentRefs
) {
}
