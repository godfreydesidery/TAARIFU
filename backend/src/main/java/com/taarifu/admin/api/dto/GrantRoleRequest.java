package com.taarifu.admin.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to grant a role to an existing account (M14, US-14.1; promotes citizen → staff/representative/
 * responder/area-official).
 *
 * <p>Responsibility: carries only the role to grant; the <b>target account</b> is the path id and the
 * <b>acting admin</b> is the authenticated caller — never a body field (so a request body can never
 * impersonate a different actor or target, mirroring the rating-fence rule that the actor key comes from
 * the security context).</p>
 *
 * @param roleName the role catalogue name to grant (e.g. {@code MODERATOR}, {@code RESPONDER_AGENT},
 *                 {@code REPRESENTATIVE}); validated against the catalogue by the identity implementation.
 */
public record GrantRoleRequest(@NotBlank String roleName) {
}
