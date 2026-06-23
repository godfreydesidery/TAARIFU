package com.taarifu.institutions.api.dto;

import java.util.UUID;

/**
 * Response DTO for a {@link com.taarifu.institutions.domain.model.ParliamentRole} (PRD §9.1, §22.6).
 *
 * <p>Responsibility: the public boundary shape for the parliament-role catalogue.</p>
 *
 * @param id          the role's public id.
 * @param code        stable role code.
 * @param name        role display name.
 * @param description role remit description, or {@code null}.
 */
public record ParliamentRoleDto(
        UUID id,
        String code,
        String name,
        String description
) {
}
