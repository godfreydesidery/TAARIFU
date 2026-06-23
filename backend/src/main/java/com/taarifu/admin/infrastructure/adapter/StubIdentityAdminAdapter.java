package com.taarifu.admin.infrastructure.adapter;

import com.taarifu.admin.api.spi.AdminPage;
import com.taarifu.admin.api.spi.AdminUserView;
import com.taarifu.admin.api.spi.IdentityAdminPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;

import java.util.List;
import java.util.UUID;

/**
 * A no-op <b>stub</b> {@link IdentityAdminPort} so the admin module boots, builds, and tests in isolation
 * <b>before</b> the {@code identity} module publishes its real implementation (ARCHITECTURE §7 "a stub
 * adapter for every port lets the whole system run with zero external calls"; ADR-0013 wiring is deferred).
 *
 * <p>Responsibility: satisfy the {@code UserAdminService} dependency without touching identity's tables.
 * It is wired only when no real {@code IdentityAdminPort} bean exists
 * ({@code @ConditionalOnMissingBean} in {@link com.taarifu.admin.infrastructure.config.AdminModuleConfig}),
 * so the moment identity registers its adapter, this stub steps aside (it never shadows the real one).</p>
 *
 * <p>Behaviour: {@link #listUsers} returns an empty page; every read/command that names a specific account
 * throws {@code NOT_FOUND} (deny-by-default — the stub knows of no accounts, so it must never silently
 * "succeed" a grant/suspend that did nothing). This makes the missing-wiring state obvious in any
 * environment that has not yet wired identity, rather than masking it. It is <b>not</b> for production —
 * production runs with the identity-backed adapter.</p>
 */
public class StubIdentityAdminAdapter implements IdentityAdminPort {

    /** @return an empty page (the stub knows of no accounts). */
    @Override
    public AdminPage<AdminUserView> listUsers(String query, String roleName, String status, int page, int size) {
        return new AdminPage<>(List.of(), page, size, 0L);
    }

    /** @throws ApiException always {@code NOT_FOUND} — the stub resolves no account. */
    @Override
    public AdminUserView getUser(UUID userPublicId) {
        throw new ApiException(ErrorCode.NOT_FOUND);
    }

    /** @throws ApiException always {@code NOT_FOUND} — no account to grant a role to (deny-by-default). */
    @Override
    public void grantRole(UUID actingAdminPublicId, UUID userPublicId, String roleName) {
        throw new ApiException(ErrorCode.NOT_FOUND);
    }

    /** @throws ApiException always {@code NOT_FOUND}. */
    @Override
    public void revokeRole(UUID actingAdminPublicId, UUID userPublicId, String roleName) {
        throw new ApiException(ErrorCode.NOT_FOUND);
    }

    /** @throws ApiException always {@code NOT_FOUND}. */
    @Override
    public void suspendUser(UUID actingAdminPublicId, UUID userPublicId, String reasonCode) {
        throw new ApiException(ErrorCode.NOT_FOUND);
    }

    /** @throws ApiException always {@code NOT_FOUND}. */
    @Override
    public void reinstateUser(UUID actingAdminPublicId, UUID userPublicId) {
        throw new ApiException(ErrorCode.NOT_FOUND);
    }
}
