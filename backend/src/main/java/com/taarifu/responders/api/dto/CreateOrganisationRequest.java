package com.taarifu.responders.api.dto;

import com.taarifu.responders.domain.model.enums.OrganisationType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO to register a responder organisation (admin CRUD, PRD §24.4).
 *
 * <p>Responsibility: validated input for {@code POST /organisations}. Bean Validation runs at the edge
 * (CLAUDE.md §8); a new organisation is created PENDING + unverified regardless of input — activation
 * and verification are explicit, separately-authorised Moderator/Admin actions (§24.4), never settable
 * at creation, so this DTO deliberately has no {@code status}/{@code verified} fields.</p>
 *
 * @param name         display name (required).
 * @param type         organisation kind (required).
 * @param contactPhone public contact phone (optional).
 * @param contactEmail public contact email (optional, validated as email).
 * @param websiteUrl   public website URL (optional).
 */
public record CreateOrganisationRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull OrganisationType type,
        @Size(max = 32) String contactPhone,
        @Email @Size(max = 200) String contactEmail,
        @Size(max = 300) String websiteUrl
) {
}
