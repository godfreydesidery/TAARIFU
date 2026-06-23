package com.taarifu.responders.api.dto;

import com.taarifu.responders.domain.model.enums.OrganisationStatus;
import com.taarifu.responders.domain.model.enums.OrganisationType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO to update a responder organisation's mutable fields (admin CRUD, PRD §24.4).
 *
 * <p>Responsibility: validated input for {@code PUT /organisations/{id}}. Allows changing name, type,
 * operational status and contacts. It deliberately excludes {@code verified} — verification is a
 * dedicated, audited Moderator/Admin action with its own endpoint (§24.4), not a field an editor can
 * silently flip while updating contacts.</p>
 *
 * @param name         display name (required).
 * @param type         organisation kind (required).
 * @param status       operational status (required; PENDING/ACTIVE/SUSPENDED/DISABLED).
 * @param contactPhone public contact phone (optional).
 * @param contactEmail public contact email (optional).
 * @param websiteUrl   public website URL (optional).
 */
public record UpdateOrganisationRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull OrganisationType type,
        @NotNull OrganisationStatus status,
        @Size(max = 32) String contactPhone,
        @Email @Size(max = 200) String contactEmail,
        @Size(max = 300) String websiteUrl
) {
}
