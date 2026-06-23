package com.taarifu.admin.application.service;

import com.taarifu.admin.api.spi.AdminPage;
import com.taarifu.admin.api.spi.AdminUserView;
import com.taarifu.admin.api.spi.IdentityAdminPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.ScopeGuard;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The admin <b>user &amp; role management</b> workflow (M14, US-14.1, UC-H06).
 *
 * <p>Responsibility: own the operator-side rules for listing accounts and granting/revoking roles and
 * suspending/reinstating accounts, then delegate the actual state change to the {@link IdentityAdminPort}
 * (implemented by the {@code identity} module). The admin module thus mutates account/role state
 * <b>without importing identity's {@code domain}/{@code repository}</b> — the boundary holds (ADR-0013 §1,
 * ARCHITECTURE §3.2).</p>
 *
 * <p><b>Conflict-of-interest fence (D16):</b> an admin may not grant/revoke a role on, nor suspend/
 * reinstate, <b>their own</b> account — that would let an operator self-escalate or shield themselves. The
 * {@code isNotSelf} guard (the same {@code ScopeGuard} the rating/verification fences use) blocks it here,
 * <b>before</b> the port is called, and a removed guard makes the dedicated unit test fail (CLAUDE.md
 * §10 — test the invariant). The deny-by-default {@code ROLE_ADMIN}/{@code ROOT} method security on the
 * controller is the outer ring; this self-action check is the inner ring (defence in depth).</p>
 *
 * <p>The acting admin's identity always comes from the <b>security context</b>
 * ({@link CurrentUser#requirePublicId()}), never a request body — a body can never name a different actor
 * (mirrors the rating fence). The identity port owns the change's transaction and its audit trail (the
 * canonical "who granted what to whom").</p>
 */
@Service
public class UserAdminService {

    private final IdentityAdminPort identityAdmin;
    private final ScopeGuard scopeGuard;

    /**
     * @param identityAdmin the published identity-administration port (account/role state changes).
     * @param scopeGuard    the conflict-of-interest guard ({@code isNotSelf}, D16) — bean {@code taarifuAuthz}.
     */
    public UserAdminService(IdentityAdminPort identityAdmin, ScopeGuard scopeGuard) {
        this.identityAdmin = identityAdmin;
        this.scopeGuard = scopeGuard;
    }

    /**
     * Lists accounts for the admin user table (privacy-minimised; masked phones only).
     *
     * @param query    optional free-text filter, or {@code null}.
     * @param roleName optional role filter, or {@code null}.
     * @param status   optional account-status filter, or {@code null}.
     * @param page     zero-based page index.
     * @param size     page size (the controller caps it).
     * @return a page of account views.
     */
    public AdminPage<AdminUserView> listUsers(String query, String roleName, String status, int page, int size) {
        return identityAdmin.listUsers(query, roleName, status, page, size);
    }

    /**
     * Loads one account's admin view.
     *
     * @param userPublicId the account's public id.
     * @return the account view.
     * @throws ApiException {@code NOT_FOUND} if no such account.
     */
    public AdminUserView getUser(UUID userPublicId) {
        return identityAdmin.getUser(userPublicId);
    }

    /**
     * Grants a role to an account (additive; promotes citizen → staff/representative/responder).
     *
     * @param userPublicId the target account.
     * @param roleName     the role to grant.
     * @throws ApiException {@code CONFLICT_OF_INTEREST} if the target is the acting admin (D16);
     *                      {@code NOT_FOUND}/{@code BAD_REQUEST} from the identity port for an unknown
     *                      account/role.
     */
    public void grantRole(UUID userPublicId, String roleName) {
        UUID actingAdmin = requireNotSelf(userPublicId);
        identityAdmin.grantRole(actingAdmin, userPublicId, roleName);
    }

    /**
     * Revokes a role from an account (sets the grant to FORMER; never hard-deletes).
     *
     * @param userPublicId the target account.
     * @param roleName     the role to revoke.
     * @throws ApiException {@code CONFLICT_OF_INTEREST} for a self-action (D16); {@code NOT_FOUND} for an
     *                      unknown account/role.
     */
    public void revokeRole(UUID userPublicId, String roleName) {
        UUID actingAdmin = requireNotSelf(userPublicId);
        identityAdmin.revokeRole(actingAdmin, userPublicId, roleName);
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
        identityAdmin.suspendUser(actingAdmin, userPublicId, reasonCode);
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
        identityAdmin.reinstateUser(actingAdmin, userPublicId);
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
