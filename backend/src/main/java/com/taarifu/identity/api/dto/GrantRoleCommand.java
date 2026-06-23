package com.taarifu.identity.api.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The command payload to grant a role to an account additively (D15), with optional attribute scope and an
 * optional effective window — published on {@link com.taarifu.identity.api.UserAdminApi} (M14, US-14.1,
 * UC-H06; PRD §7.1, §9.1, D20, N-2).
 *
 * <p>Responsibility: carry exactly what identity needs to materialise a {@code RoleAssignment} — the role
 * catalogue name, the area/category/constituency scope the grant is limited to, and the
 * {@code effectiveFrom}/{@code effectiveTo} window — and nothing else. The <b>target account</b> and the
 * <b>acting admin</b> are parameters of the port method (path id and security principal respectively), never
 * fields here, so the command can never name a different actor or target (mirrors the rating-fence rule that
 * the actor key comes from the security context).</p>
 *
 * <p>Scope is opaque {@code UUID}s — area/category/constituency public ids resolved through the relevant
 * modules' own apis — so no geography/reporting entity leaks across the boundary (ADR-0013 §1). An empty/
 * {@code null} scope set means "unrestricted within that role" (the live {@code ScopeGuard} convention). A
 * {@code null} {@code effectiveFrom} means "effective on creation"; a {@code null} {@code effectiveTo} means
 * "open-ended" (per {@code RoleAssignment}).</p>
 *
 * @param roleName       the role catalogue name to grant (e.g. {@code MODERATOR}, {@code RESPONDER_AGENT},
 *                       {@code REPRESENTATIVE}); validated against the catalogue by the identity impl.
 * @param areaIds        area-scope public ids to limit the grant to, or {@code null}/empty for unrestricted.
 * @param categoryIds    issue-category-scope public ids to limit the grant to, or {@code null}/empty.
 * @param constituencyId the single constituency-scope public id, or {@code null} if not constituency-scoped.
 * @param effectiveFrom  when the grant takes effect (UTC), or {@code null} = effective on creation.
 * @param effectiveTo    when the grant ends (UTC), or {@code null} = open-ended.
 */
public record GrantRoleCommand(
        String roleName,
        Set<UUID> areaIds,
        Set<UUID> categoryIds,
        UUID constituencyId,
        Instant effectiveFrom,
        Instant effectiveTo) {
}
