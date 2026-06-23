package com.taarifu.admin.api.spi;

import java.util.UUID;

/**
 * The <b>identity-administration SPI</b> the admin console uses to list accounts and to grant/revoke
 * roles and suspend/reinstate accounts — implemented by the {@code identity} module, called by the
 * {@code admin} module (M14, US-14.1, UC-H06; ADR-0013 §1, ARCHITECTURE §3.2).
 *
 * <p>Responsibility: keep the admin module free of any identity {@code domain}/{@code repository} import.
 * Account/role state lives in identity's tables and must be mutated there (one writer of the
 * {@code app_user}/{@code role_assignment} aggregate); the admin module owns the <b>operator workflow</b>
 * (who may do it, audit) but delegates the actual state change through this published port. Identity
 * registers the implementation as a {@code @Component} in its {@code application.service} layer; admin
 * injects the interface (ADR-0013 §1 — caller injects the interface, never the impl).</p>
 *
 * <p>WHY this is a command port (mutates), unlike the read-only {@code *QueryApi} of ADR-0013 §1: role
 * granting/suspension is a synchronous, transactional change the operator must see succeed or fail in the
 * same request (an admin cannot wait for an async event to know whether a suspension took). It is a
 * sanctioned feature-of-admin → foundation-identity {@code api} dependency (admin is a foundation module
 * that may call identity's published api); it introduces <b>no cycle</b> (identity does not call admin).
 * ADR-0013 §4's fourth revisit-trigger ("a synchronous cross-feature command that is not metering") is
 * exactly this case — recorded as a CENTRAL INTEGRATION NEED for the architect to ratify.</p>
 *
 * <p><b>Authorization & conflict-of-interest:</b> the admin controller enforces {@code ROLE_ADMIN}/{@code
 * ROOT} (deny-by-default) and the no-self-action guard (an admin may not grant themselves a role or
 * suspend their own account — D16) <b>before</b> calling this port; the identity implementation may
 * additionally validate the target/role and is the single place the change + its audit happen.</p>
 *
 * <p><b>Privacy:</b> {@link #listUsers} returns {@link AdminUserView}s carrying masked phones only — never
 * raw PII (PRD §18, PDPA).</p>
 */
public interface IdentityAdminPort {

    /**
     * Lists accounts for the admin user-management table, filtered and paginated.
     *
     * @param query    optional free-text filter (matched by the implementation over handle/phone-suffix);
     *                 {@code null}/blank means no text filter.
     * @param roleName optional role-name filter (only accounts holding an active grant of this role);
     *                 {@code null} means no role filter. An unknown role name yields an empty page.
     * @param status   optional account-status filter ({@code ACTIVE}/{@code SUSPENDED}/…); {@code null}
     *                 means any status.
     * @param page     zero-based page index.
     * @param size     page size (the admin controller caps this before calling).
     * @return a page of privacy-minimised account views (never {@code null}).
     */
    AdminPage<AdminUserView> listUsers(String query, String roleName, String status, int page, int size);

    /**
     * Loads a single account's admin view by public id.
     *
     * @param userPublicId the account's public id.
     * @return the account view.
     * @throws com.taarifu.common.error.ApiException with {@code NOT_FOUND} if no such account exists.
     */
    AdminUserView getUser(UUID userPublicId);

    /**
     * Grants a role to an existing account (additive — the account keeps its other roles, §6.4). Used to
     * promote a citizen to staff/representative/responder/area-official, etc.
     *
     * <p>Idempotent at the implementation: granting a role the account already holds (active) is a no-op
     * success, not a duplicate. The implementation validates {@code roleName} against the catalogue and
     * audits the grant ({@code actor} = the calling admin, {@code subject} = the target account).</p>
     *
     * @param actingAdminPublicId the admin performing the grant (for the identity-side audit / multi-hat
     *                            context, D16).
     * @param userPublicId        the target account.
     * @param roleName            the role catalogue name to grant (e.g. {@code MODERATOR},
     *                            {@code RESPONDER_AGENT}, {@code REPRESENTATIVE}).
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND} (unknown account/role) or
     *                                               {@code BAD_REQUEST} (un-grantable role).
     */
    void grantRole(UUID actingAdminPublicId, UUID userPublicId, String roleName);

    /**
     * Revokes an account's role (sets the grant to {@code FORMER}; never hard-deletes — history is kept,
     * §6.4/§18). Revoking a role the account does not hold is a no-op success.
     *
     * @param actingAdminPublicId the admin performing the revoke (audit/multi-hat, D16).
     * @param userPublicId        the target account.
     * @param roleName            the role to revoke.
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND} for an unknown account/role.
     */
    void revokeRole(UUID actingAdminPublicId, UUID userPublicId, String roleName);

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
