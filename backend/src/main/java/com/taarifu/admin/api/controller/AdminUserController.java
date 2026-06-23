package com.taarifu.admin.api.controller;

import com.taarifu.admin.api.dto.GrantRoleRequest;
import com.taarifu.admin.api.dto.SuspendUserRequest;
import com.taarifu.admin.api.spi.AdminPage;
import com.taarifu.admin.api.spi.AdminUserView;
import com.taarifu.admin.application.service.UserAdminService;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.api.dto.PageMeta;
import com.taarifu.common.pagination.PageRequestFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The admin <b>user &amp; role management</b> surface (M14, US-14.1, UC-H06).
 *
 * <p>Responsibility: a thin HTTP layer for listing accounts and granting/revoking roles and suspending/
 * reinstating accounts, delegating to {@link UserAdminService} (which enforces the no-self-action fence
 * and delegates the state change to the identity module via the published
 * {@link com.taarifu.admin.api.spi.IdentityAdminPort}). The admin module never imports identity's
 * internals (ADR-0013 §1, ARCHITECTURE §3.2). No business logic, no {@code @Transactional} here.</p>
 *
 * <p><b>Authorization (deny-by-default, defence in depth):</b></p>
 * <ul>
 *   <li>{@code hasAnyRole('ADMIN','ROOT')} on every method — RBAC; identity/role admin is a back-office
 *       power (PRD §7.1). Role <i>granting</i> of the highest roles is itself sensitive; the granular
 *       "who may grant which role" policy is owned by the identity implementation (it validates the
 *       target role).</li>
 *   <li>{@code @taarifuAuthz.isNotSelf(#userPublicId)} on the mutating endpoints — D16 conflict-of-interest:
 *       an admin can neither escalate their own roles nor shield/suspend their own account. The service
 *       re-checks the same guard (the controller check fails fast; the service check is the load-bearing,
 *       unit-tested one).</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/admin/users")
@Tag(name = "Admin Users", description = "Account listing and role/suspension management (back-office).")
public class AdminUserController {

    private final UserAdminService userAdminService;
    private final PageRequestFactory pageRequests;
    private final ResponseFactory responses;

    /**
     * @param userAdminService user/role workflow service.
     * @param pageRequests     reuses the kernel's page-size cap/defaults for the {@code size} param.
     * @param responses        envelope builder.
     */
    public AdminUserController(UserAdminService userAdminService, PageRequestFactory pageRequests,
                               ResponseFactory responses) {
        this.userAdminService = userAdminService;
        this.pageRequests = pageRequests;
        this.responses = responses;
    }

    /**
     * Lists accounts (privacy-minimised; masked phones only), filtered and paginated.
     *
     * @param q      optional free-text filter, or {@code null}.
     * @param role   optional role-name filter, or {@code null}.
     * @param status optional account-status filter, or {@code null}.
     * @param page   zero-based page index (defaults 0).
     * @param size   page size (capped at the kernel's {@code MAX_SIZE}).
     * @return {@code 200} + a paged list of account views with pagination {@code meta}.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "List accounts for management (masked PII)")
    public ApiResponse<List<AdminUserView>> listUsers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        // Reuse the kernel page-size cap (DoS/data-budget guard, PRD §15) even though the port paginates.
        var pageable = pageRequests.of(page, size, null);
        AdminPage<AdminUserView> result = userAdminService.listUsers(
                q, role, status, pageable.getPageNumber(), pageable.getPageSize());
        PageMeta meta = new PageMeta(result.page(), result.size(), result.totalElements(), result.totalPages());
        return responses.paged(result.content(), meta);
    }

    /**
     * Loads one account's admin view.
     *
     * @param userPublicId the account's public id.
     * @return {@code 200} + the account view.
     */
    @GetMapping("/{userPublicId}")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "Get one account's admin view")
    public ApiResponse<AdminUserView> getUser(@PathVariable UUID userPublicId) {
        return responses.ok(userAdminService.getUser(userPublicId));
    }

    /**
     * Grants a role to an account (additive; promotes citizen → staff/representative/responder).
     *
     * @param userPublicId the target account.
     * @param request      the role to grant.
     * @return {@code 200} (empty body).
     */
    @PostMapping("/{userPublicId}/roles")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT') and @taarifuAuthz.isNotSelf(#userPublicId)")
    @Operation(summary = "Grant a role to an account")
    public ApiResponse<Void> grantRole(@PathVariable UUID userPublicId,
                                       @Valid @RequestBody GrantRoleRequest request) {
        userAdminService.grantRole(userPublicId, request.roleName());
        return responses.ok(null);
    }

    /**
     * Revokes a role from an account (sets the grant to FORMER; never hard-deletes).
     *
     * @param userPublicId the target account.
     * @param roleName     the role to revoke.
     * @return {@code 200} (empty body).
     */
    @DeleteMapping("/{userPublicId}/roles/{roleName}")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT') and @taarifuAuthz.isNotSelf(#userPublicId)")
    @Operation(summary = "Revoke a role from an account")
    public ApiResponse<Void> revokeRole(@PathVariable UUID userPublicId, @PathVariable String roleName) {
        userAdminService.revokeRole(userPublicId, roleName);
        return responses.ok(null);
    }

    /**
     * Suspends an account so it can no longer authenticate/act (recoverable).
     *
     * @param userPublicId the target account.
     * @param request      optional machine reason code, or empty.
     * @return {@code 200} (empty body).
     */
    @PostMapping("/{userPublicId}/suspend")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT') and @taarifuAuthz.isNotSelf(#userPublicId)")
    @Operation(summary = "Suspend an account (recoverable)")
    public ApiResponse<Void> suspendUser(@PathVariable UUID userPublicId,
                                         @Valid @RequestBody(required = false) SuspendUserRequest request) {
        String reasonCode = request == null ? null : request.reasonCode();
        userAdminService.suspendUser(userPublicId, reasonCode);
        return responses.ok(null);
    }

    /**
     * Reinstates a suspended account to ACTIVE.
     *
     * @param userPublicId the target account.
     * @return {@code 200} (empty body).
     */
    @PostMapping("/{userPublicId}/reinstate")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT') and @taarifuAuthz.isNotSelf(#userPublicId)")
    @Operation(summary = "Reinstate a suspended account")
    public ApiResponse<Void> reinstateUser(@PathVariable UUID userPublicId) {
        userAdminService.reinstateUser(userPublicId);
        return responses.ok(null);
    }
}
