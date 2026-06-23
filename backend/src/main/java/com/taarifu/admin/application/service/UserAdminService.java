package com.taarifu.admin.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.identity.api.UserAdminApi;
import com.taarifu.identity.api.UserAdminQueryApi;
import com.taarifu.identity.api.dto.GrantRoleCommand;
import com.taarifu.identity.api.dto.UserAdminDetail;
import com.taarifu.identity.api.dto.UserAdminFilter;
import com.taarifu.identity.api.dto.UserAdminPage;
import com.taarifu.identity.api.dto.UserAdminSummary;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The admin console's <b>user &amp; role management</b> workflow (M14, US-14.1, UC-H06).
 *
 * <p>Responsibility: own the operator-side rules for listing accounts and granting/revoking roles and
 * suspending/reinstating accounts, then delegate the actual read/state-change to the identity module's
 * published ports — the read {@link UserAdminQueryApi} and the command {@link UserAdminApi} (ADR-0013 §1,
 * the same {@code admin → identity.api} shape {@code ReportsAdminService} uses for reporting). The admin
 * module thus mutates account/role state <b>without importing identity's {@code domain}/{@code repository}</b>
 * — the boundary holds (ARCHITECTURE §3.2). No {@code @Transactional} here: each identity port owns its own
 * transaction over identity's tables; this service only orchestrates and applies the conflict-of-interest
 * fence.</p>
 *
 * <p><b>Conflict-of-interest fence (D16):</b> an admin may not grant/revoke a role on, nor suspend/reinstate,
 * <b>their own</b> account — that would let an operator self-escalate or shield themselves. The
 * {@code isNotSelf} guard (the same {@code ScopeGuard} the rating/verification fences use) blocks it here,
 * <b>before</b> the identity port is called, and a removed guard makes the dedicated unit test fail
 * (CLAUDE.md §10 — test the invariant). The deny-by-default {@code ROLE_ADMIN}/{@code ROOT} method security
 * on the controller is the outer ring; this self-action check is the inner ring (defence in depth).</p>
 *
 * <p>The acting admin's identity always comes from the <b>security context</b>
 * ({@link CurrentUser#requirePublicId()}), never a request body — a body can never name a different actor
 * (mirrors the rating fence). The identity port owns the change's transaction and its audit trail (the
 * canonical "who granted what to whom").</p>
 */
@Service
public class UserAdminService {

    private final UserAdminQueryApi userQuery;
    private final UserAdminApi userAdmin;
    private final ScopeGuard scopeGuard;

    /**
     * @param userQuery  the published identity read port (account list + detail) — injected as the
     *                   interface, never the impl (ADR-0013 §1).
     * @param userAdmin  the published identity command port (grant/revoke/suspend/reinstate).
     * @param scopeGuard the conflict-of-interest guard ({@code isNotSelf}, D16) — bean {@code taarifuAuthz}.
     */
    public UserAdminService(UserAdminQueryApi userQuery, UserAdminApi userAdmin, ScopeGuard scopeGuard) {
        this.userQuery = userQuery;
        this.userAdmin = userAdmin;
        this.scopeGuard = scopeGuard;
    }

    /**
     * Lists accounts for the admin user table (privacy-minimised; masked phones only).
     *
     * @param filter the optional filter dimensions (name/phone-suffix/tier/role/status).
     * @param page   zero-based page index.
     * @param size   page size (the controller caps it).
     * @return a page of account summaries.
     */
    public UserAdminPage<UserAdminSummary> listUsers(UserAdminFilter filter, int page, int size) {
        return userQuery.listUsers(filter, page, size);
    }

    /**
     * Loads one account's admin detail (roles + scopes, tier, status, location count; no raw PII).
     *
     * @param userPublicId the account's public id.
     * @return the account detail.
     * @throws ApiException {@code NOT_FOUND} if no such account.
     */
    public UserAdminDetail getUser(UUID userPublicId) {
        return userQuery.getUser(userPublicId);
    }

    /**
     * Grants a role to an account additively (D15) with optional scope + effective window.
     *
     * @param userPublicId the target account.
     * @param command      the role + optional scope/effective window to grant.
     * @return the granted {@code RoleAssignment} public id (or the existing one, on the idempotent path).
     * @throws ApiException {@code CONFLICT_OF_INTEREST} if the target is the acting admin (D16);
     *                      {@code NOT_FOUND}/{@code BAD_REQUEST} from the identity port for an unknown
     *                      account / constituency / un-grantable role.
     */
    public UUID grantRole(UUID userPublicId, GrantRoleCommand command) {
        UUID actingAdmin = requireNotSelf(userPublicId);
        return userAdmin.grantRole(actingAdmin, userPublicId, command);
    }

    /**
     * Revokes (end-dates) a specific role grant by its assignment id (never hard-deletes).
     *
     * @param userPublicId       the target account.
     * @param assignmentPublicId the role-assignment public id to revoke.
     * @throws ApiException {@code CONFLICT_OF_INTEREST} for a self-action (D16); {@code NOT_FOUND} for an
     *                      unknown account/assignment; {@code BAD_REQUEST} if it is the last CITIZEN grant.
     */
    public void revokeRole(UUID userPublicId, UUID assignmentPublicId) {
        UUID actingAdmin = requireNotSelf(userPublicId);
        userAdmin.revokeRole(actingAdmin, userPublicId, assignmentPublicId);
    }

    /**
     * Suspends an account so it can no longer authenticate/act (recoverable).
     *
     * @param userPublicId the target account.
     * @param reasonCode   an optional machine reason for the audit trail, or {@code null}.
     * @throws ApiException {@code CONFLICT_OF_INTEREST} if the admin targets themselves (D16);
     *                      {@code NOT_FOUND} for an unknown account.
     */
    public void suspendUser(UUID userPublicId, String reasonCode) {
        UUID actingAdmin = requireNotSelf(userPublicId);
        userAdmin.suspendUser(actingAdmin, userPublicId, reasonCode);
    }

    /**
     * Reinstates a suspended account to ACTIVE.
     *
     * @param userPublicId the target account.
     * @throws ApiException {@code CONFLICT_OF_INTEREST} for a self-action (D16); {@code NOT_FOUND} for an
     *                      unknown account.
     */
    public void reinstateUser(UUID userPublicId) {
        UUID actingAdmin = requireNotSelf(userPublicId);
        userAdmin.reinstateUser(actingAdmin, userPublicId);
    }

    /**
     * Resolves the acting admin from the security context and blocks a self-targeting action (D16).
     *
     * @param targetPublicId the account the action targets.
     * @return the acting admin's public id (guaranteed not equal to the target).
     * @throws ApiException {@code CONFLICT_OF_INTEREST} if the target is the acting admin.
     */
    private UUID requireNotSelf(UUID targetPublicId) {
        UUID actingAdmin = CurrentUser.requirePublicId();
        if (!scopeGuard.isNotSelf(targetPublicId)) {
            throw new ApiException(ErrorCode.CONFLICT_OF_INTEREST);
        }
        return actingAdmin;
    }
}
