package com.taarifu.reporting.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO to file a report (PRD §10 US-3.1, UC-D01).
 *
 * <p>Responsibility: the validated boundary input for the file-report flow. The citizen supplies the
 * category, title/description, an optional GPS point, the resolved ward (minimum pin granularity), an
 * optional visibility choice, the {@code anonymous} flag, and attachment references.</p>
 *
 * <p>WHY visibility is a nullable client <i>preference</i>: a sensitive category <b>forces</b> PRIVATE and
 * may ignore this value (Appendix D.4, D-Q1); when absent and not forced, the category's default applies.
 * WHY {@code anonymous} is a request flag (not derived): only sensitive categories honour it (T1
 * sufficient, no reporter linkage stored); for a non-sensitive category the service rejects anonymity and
 * requires T2 (Appendix D.4). Keeping it explicit makes the citizen's intent auditable.</p>
 *
 * @param categoryId   the chosen issue category's public id (required).
 * @param title        short title (required, ≤ 200).
 * @param description  free-text description (required, ≤ 4000).
 * @param wardId       resolved ward public id (required; minimum pin granularity, PRD §9.0).
 * @param latitude     optional incident latitude (WGS84, −90..90); paired with {@code longitude}.
 * @param longitude    optional incident longitude (WGS84, −180..180); paired with {@code latitude}.
 * @param visibility   optional visibility preference ({@code PUBLIC}/{@code PRIVATE}); may be overridden.
 * @param anonymous    {@code true} to file without identity linkage (only honoured for sensitive categories).
 * @param attachmentRefs optional object-store references to scanned attachments.
 */
public record FileReportDto(
        @NotNull UUID categoryId,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 4000) String description,
        @NotNull UUID wardId,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @Size(max = 16) String visibility,
        boolean anonymous,
        @Size(max = 20) List<@Size(max = 200) String> attachmentRefs
) {
}
