package com.taarifu.identity.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One of an account's role grants — the role plus its attribute scope and effective window — published on
 * {@link com.taarifu.identity.api.UserAdminQueryApi} for the admin detail view (M14, US-14.1; PRD §7.1,
 * §9.1, D20).
 *
 * <p>Responsibility: surface to the operator <b>what authority an account holds and how it is scoped</b>, so
 * an admin can audit a promotion and address a specific grant for a later revoke. The {@code assignmentId}
 * is the {@code RoleAssignment} public id the {@code DELETE …/roles/{assignmentId}} command targets — an
 * account may hold several grants of the same role with different scopes, so the role name alone cannot
 * identify the one to revoke (this is why revoke is by assignment id, not by role name).</p>
 *
 * <p>Scope crosses the boundary as opaque {@code UUID} sets/ids — the area/category/constituency public ids
 * the grant is limited to — exactly as stored on the {@code RoleAssignment} (no geography/reporting entity
 * leaks; the console resolves names through those modules' own apis if it wants labels). An <b>empty</b>
 * scope set means "unrestricted within that role" (the live {@code ScopeGuard} convention). No PII here.</p>
 *
 * @param assignmentId   the {@code RoleAssignment} public id (the id a revoke command targets).
 * @param roleName       the granted role catalogue name (e.g. {@code MODERATOR}, {@code RESPONDER_AGENT}).
 * @param status         the grant lifecycle status name ({@code PENDING_VERIFICATION}/{@code ACTIVE}/
 *                       {@code SUSPENDED}/{@code FORMER}).
 * @param areaIds        area-scope public ids the grant is limited to; empty = unrestricted by area.
 * @param categoryIds    issue-category-scope public ids the grant is limited to; empty = unrestricted.
 * @param constituencyId the single constituency-scope public id, or {@code null} if not constituency-scoped.
 * @param effectiveFrom  when the grant takes effect (UTC), or {@code null} = effective on creation.
 * @param effectiveTo    when the grant ends (UTC), or {@code null} = open-ended.
 */
public record UserRoleGrant(
        UUID assignmentId,
        String roleName,
        String status,
        List<UUID> areaIds,
        List<UUID> categoryIds,
        UUID constituencyId,
        Instant effectiveFrom,
        Instant effectiveTo) {
}
