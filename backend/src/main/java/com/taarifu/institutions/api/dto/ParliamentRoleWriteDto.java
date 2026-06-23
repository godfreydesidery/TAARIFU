package com.taarifu.institutions.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin write DTO for creating or updating a
 * {@link com.taarifu.institutions.domain.model.ParliamentRole} (PRD §9.1; UC-B13 CRUD Parliament role).
 *
 * <p>Responsibility: the validated request body for admin parliament-role CRUD. {@code code} is the
 * idempotent identity key and is immutable on update (service-enforced).</p>
 *
 * @param code        stable role code (required, unique); immutable on update.
 * @param name        role display name (required).
 * @param description role remit description, or {@code null}.
 */
public record ParliamentRoleWriteDto(
        @NotBlank @Size(max = 48) String code,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 512) String description
) {
}
