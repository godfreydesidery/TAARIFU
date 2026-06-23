package com.taarifu.responders.api.dto;

import java.util.UUID;

/**
 * Response DTO for a responder {@link com.taarifu.responders.domain.model.Organisation} (PRD §24.1).
 *
 * <p>Responsibility: the boundary shape for organisation reads — the public directory and admin views.
 * Only the {@code publicId} is exposed, never the internal numeric id (ADR-0006). Contact fields are
 * the organisation's <b>public</b> contacts (directory display), never a citizen's PII.</p>
 *
 * <p>WHY {@code status}/{@code verified} are included: admin clients need them; for the public
 * directory they are always {@code ACTIVE}/{@code true} by construction (only listable orgs are
 * returned), so exposing them is safe and keeps one DTO for both audiences (KISS).</p>
 *
 * @param id           the organisation's public id (UUID).
 * @param name         display name.
 * @param type         organisation kind (e.g. {@code PARASTATAL}).
 * @param status       operational status (e.g. {@code ACTIVE}).
 * @param verified     whether verified by a Moderator/Admin (§24.4).
 * @param contactPhone public contact phone, or {@code null}.
 * @param contactEmail public contact email, or {@code null}.
 * @param websiteUrl   public website URL, or {@code null}.
 */
public record OrganisationDto(
        UUID id,
        String name,
        String type,
        String status,
        boolean verified,
        String contactPhone,
        String contactEmail,
        String websiteUrl
) {
}
