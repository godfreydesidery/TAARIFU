package com.taarifu.admin.api.dto;

import java.util.UUID;

/**
 * The response body of a successful role grant (M14, US-14.1): the public id of the created (or, on the
 * idempotent path, the existing active) {@code RoleAssignment}.
 *
 * <p>Responsibility: hand the admin console the assignment id it needs to address this specific grant for a
 * later revoke ({@code DELETE …/roles/{assignmentId}}). WHY return it (rather than an empty 200): an account
 * may hold several grants of the same role with different scopes, so the console cannot revoke by role name
 * alone — it needs the concrete assignment id, and returning it here avoids a follow-up detail round-trip.</p>
 *
 * @param assignmentId the granted (or existing active) {@code RoleAssignment} public id.
 */
public record RoleGrantedDto(UUID assignmentId) {
}
