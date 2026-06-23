package com.taarifu.accountability.api.dto;

import com.taarifu.accountability.domain.model.enums.ContributionType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Public response DTO for a {@link com.taarifu.accountability.domain.model.RepresentativeContribution}
 * (PRD §10 Epic M6, US-6.1).
 *
 * <p>Responsibility: the read shape citizens see. Carries provenance ({@code sourceUrl}) so the record is
 * attributable (EI-11). Attachment references are object-store keys the client resolves via signed URLs,
 * never raw bytes (PRD §18).</p>
 *
 * @param id                the contribution's public id.
 * @param representativeId  the subject representative's public id (institutions module).
 * @param type              the contribution kind.
 * @param title             short title.
 * @param summary           longer summary, or {@code null}.
 * @param occurredOn        the sitting date.
 * @param parliamentSession session/term reference, or {@code null}.
 * @param sourceUrl         provenance URL, or {@code null}.
 * @param attachmentRefs    object-store references (may be empty).
 */
public record ContributionDto(
        UUID id,
        UUID representativeId,
        ContributionType type,
        String title,
        String summary,
        LocalDate occurredOn,
        String parliamentSession,
        String sourceUrl,
        List<String> attachmentRefs
) {
}
