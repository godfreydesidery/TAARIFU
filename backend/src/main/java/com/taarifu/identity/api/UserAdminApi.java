package com.taarifu.identity.api;

import com.taarifu.identity.api.dto.GrantRoleCommand;

import java.util.UUID;

/**
 * The identity module's <b>public, in-process command port</b> for back-office account/role administration
 * (M14, US-14.1, UC-H06; ADR-0013 §1, §4d). The {@code admin} module calls this synchronously
 * ({@code admin → identity}) to grant/revoke roles and suspend/reinstate accounts, <b>without</b> importing
 * identity's {@code domain}/{@code repository} (ARCHITECTURE §3.2).
 *
 * <p>WHY a command port (mutates), separate from {@link UserAdminQueryApi} (ISP): role
 * granting/suspension is a synchronous, transactional change the operator must see succeed or fail in the
 * same request — an admin cannot wait for an async event to know whether a suspension took. This is exactly
 * ADR-0013 §4's fourth revisit-trigger ("a synchronous cross-feature command that is not metering"); it is a
 * sanctioned {@code admin → identity.api} edge that introduces <b>no cycle</b> (identity does not call admin)
 * and keeps the same {@code TokenLedgerApi}-shaped contract. Recorded as a CENTRAL INTEGRATION NEED for the
 * architect to ratify.</p>
 *
 * <p><b>One writer of the aggregate.</b> Account/role state lives in identity's tables and is mutated only
 * here (one writer of {@code app_user}/{@code role_assignment}). The admin module owns the <b>operator
 * workflow</b> (who may act, the no-self-action fence) and delegates the actual state change + its audit
 * through this port. The identity implementation additionally validates the target/role, applies the
 * <b>additive</b> role rule (D15), and writes the canonical audit row ("who granted/revoked/suspended what,
 * for whom").</p>
 *
 * <p><b>Authorization & conflict-of-interest (defence in depth):</b> the admin controller enforces
 * {@code ROLE_ADMIN}/{@code ROOT} (deny-by-default) and the no-self-action guard (an admin may not grant
 * themselves a role nor suspend their own account — D16) <b>before</b> calling this port; the acting admin
 * is passed as a parameter for the identity-side audit only — it is the security-context principal, never a
 * request-body field. Errors cross the boundary as the shared {@code common.error.ApiException}
 * ({@code NOT_FOUND}, {@code BAD_REQUEST}) so the caller never invents a second error vocabulary.</p>
 */
public interface UserAdminApi {

    /**
     * Grants a role to an existing account <b>additively</b> (D15) — the account keeps its other roles
     * (§6.4) — with optional attribute scope and an optional effective window (N-2).
     *
     * <p>Used to promote a citizen to staff/representative/responder/area-official, etc. <b>Idempotent</b>:
     * granting a role the account already holds active (with no scope change) is a no-op success, not a
     * duplicate. The implementation validates {@code command.roleName()} against the catalogue, attaches the
     * area/category/constituency scope, sets the effective window, and audits the grant ({@code actor} = the
     * calling admin, {@code subject} = the target account).</p>
     *
     * @param actingAdminPublicId the admin performing the grant (for the identity-side audit / multi-hat
     *                            context, D16) — the authenticated principal, never a body id.
     * @param userPublicId        the target account.
     * @param command             the role to grant + optional scope (areaIds/categoryIds/constituencyId) +
     *                            optional effective window.
     * @return the public id of the {@code RoleAssignment} created (or the existing active one, on the
     *         idempotent path), so the admin console can address it for a later revoke.
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND} (unknown account / unknown
     *                                               constituency scope) or {@code BAD_REQUEST} (unknown /
     *                                               un-grantable role name).
     */
    UUID grantRole(UUID actingAdminPublicId, UUID userPublicId, GrantRoleCommand command);

    /**
     * Revokes a specific role grant by its assignment public id — <b>end-dates</b> it ({@code FORMER}; never
     * a hard delete, so history/audit is kept, §6.4/§18).
     *
     * @param actingAdminPublicId the admin performing the revoke (audit/multi-hat, D16).
     * @param userPublicId        the target account (the assignment must belong to it — ownership guard).
     * @param assignmentPublicId  the {@code RoleAssignment} public id to revoke.
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND} if the account or the assignment does
     *                                               not exist, or the assignment does not belong to the
     *                                               account; {@code BAD_REQUEST} if the grant is the
     *                                               account's last {@code CITIZEN} base role (it may not be
     *                                               revoked — every account keeps its base role).
     */
    void revokeRole(UUID actingAdminPublicId, UUID userPublicId, UUID assignmentPublicId);

    /**
     * Suspends an account ({@code UserStatus.SUSPENDED}) so it can no longer authenticate/act, recoverably.
     *
     * @param actingAdminPublicId the admin performing the suspension (audit, D16).
     * @param userPublicId        the target account.
     * @param reasonCode          a machine reason for the audit trail (never PII); may be {@code null}.
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND} for an unknown account.
     */
    void suspendUser(UUID actingAdminPublicId, UUID userPublicId, String reasonCode);

    /**
     * Reinstates a suspended account back to {@code ACTIVE}.
     *
     * @param actingAdminPublicId the admin performing the reinstatement (audit, D16).
     * @param userPublicId        the target account.
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND} for an unknown account.
     */
    void reinstateUser(UUID actingAdminPublicId, UUID userPublicId);
}
