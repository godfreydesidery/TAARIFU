package com.taarifu.admin.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Request to grant a role to an existing account additively (M14, US-14.1, D15; promotes citizen → staff/
 * representative/responder/area-official), with optional attribute scope and an optional effective window
 * (N-2).
 *
 * <p>Responsibility: carries the role to grant plus its optional scope (area/category/constituency public
 * ids) and effective dates; the <b>target account</b> is the path id and the <b>acting admin</b> is the
 * authenticated caller — never a body field (so a request body can never impersonate a different actor or
 * target, mirroring the rating-fence rule that the actor key comes from the security context). The admin
 * controller maps this 1:1 to the identity port's {@link com.taarifu.identity.api.dto.GrantRoleCommand};
 * the identity implementation validates the role name and the constituency scope.</p>
 *
 * @param roleName       the role catalogue name to grant (e.g. {@code MODERATOR}, {@code RESPONDER_AGENT},
 *                       {@code REPRESENTATIVE}); validated against the catalogue by the identity impl.
 * @param areaIds        area-scope public ids to limit the grant to, or {@code null}/empty = unrestricted.
 * @param categoryIds    issue-category-scope public ids to limit the grant to, or {@code null}/empty.
 * @param constituencyId the single constituency-scope public id, or {@code null} if not constituency-scoped.
 * @param effectiveFrom  when the grant takes effect (UTC), or {@code null} = effective on creation.
 * @param effectiveTo    when the grant ends (UTC), or {@code null} = open-ended.
 */
public record GrantRoleRequest(
        @NotBlank String roleName,
        Set<UUID> areaIds,
        Set<UUID> categoryIds,
        UUID constituencyId,
        Instant effectiveFrom,
        Instant effectiveTo) {
}
