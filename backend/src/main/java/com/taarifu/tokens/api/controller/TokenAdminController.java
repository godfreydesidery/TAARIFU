package com.taarifu.tokens.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.tokens.api.dto.ActionCostPolicyDto;
import com.taarifu.tokens.api.dto.TokenRewardDto;
import com.taarifu.tokens.api.dto.UpsertActionCostPolicyRequest;
import com.taarifu.tokens.api.dto.UpsertTokenRewardRequest;
import com.taarifu.tokens.application.service.TokenAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin REST surface for the token-economy configuration — cost/quota policies and behaviour rewards
 * (PRD §23.4 admin-tunable config; M17).
 *
 * <p>Responsibility: thin HTTP layer for listing/upserting/retiring {@code ActionCostPolicy} and
 * {@code TokenReward}, delegating to {@link TokenAdminService} and wrapping results in the single
 * {@link ApiResponse} envelope. No business logic, no {@code @Transactional} (ARCHITECTURE.md §3.3).</p>
 *
 * <p><b>Authorization (deny-by-default, ARCHITECTURE.md §6.2):</b> every method is {@code
 * @PreAuthorize("hasRole('ADMIN')")} — token-economy config is a platform-admin power (PRD §23.4). Spring
 * maps the JWT role {@code ADMIN} to authority {@code ROLE_ADMIN}, matching {@code hasRole('ADMIN')}.</p>
 *
 * <p><b>Fence note (D18):</b> {@link TokenAdminService} rejects any policy whose action code is a binding
 * democratic action, so an admin cannot configure a token cost that would gate a signature/rating/poll —
 * the integrity fence is un-configurable-around (PRD §23 fence).</p>
 */
@RestController
@RequestMapping(path = "/admin/tokens")
@Tag(name = "Token Admin", description = "Admin configuration of token costs, free quotas, and rewards.")
public class TokenAdminController {

    private final TokenAdminService adminService;
    private final ResponseFactory responses;

    /**
     * @param adminService config CRUD orchestration.
     * @param responses    envelope builder.
     */
    public TokenAdminController(TokenAdminService adminService, ResponseFactory responses) {
        this.adminService = adminService;
        this.responses = responses;
    }

    // ---- ActionCostPolicy --------------------------------------------------------------------------

    /**
     * Lists all active cost/quota policies.
     *
     * @return an envelope carrying the active policies.
     */
    @GetMapping("/policies")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List active cost/quota policies")
    public ApiResponse<List<ActionCostPolicyDto>> listPolicies() {
        return responses.ok(adminService.listPolicies());
    }

    /**
     * Creates or supersedes the active cost/quota policy for an {@code (actionCode, role)}.
     *
     * @param request the validated upsert request.
     * @return an envelope carrying the new active policy.
     */
    @PostMapping("/policies")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create or supersede a cost/quota policy",
            description = "Deactivates the prior active policy for the key and inserts a new version (audited).")
    public ApiResponse<ActionCostPolicyDto> upsertPolicy(
            @Valid @RequestBody UpsertActionCostPolicyRequest request) {
        return responses.ok(adminService.upsertPolicy(request));
    }

    /**
     * Retires (deactivates) a cost/quota policy by public id.
     *
     * @param policyId the policy's public id.
     * @return an empty success envelope.
     */
    @DeleteMapping("/policies/{policyId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Retire a cost/quota policy (deactivate, never delete)")
    public ApiResponse<Void> deactivatePolicy(@PathVariable UUID policyId) {
        adminService.deactivatePolicy(policyId);
        return responses.ok(null);
    }

    // ---- TokenReward -------------------------------------------------------------------------------

    /**
     * Lists all active behaviour rewards.
     *
     * @return an envelope carrying the active rewards.
     */
    @GetMapping("/rewards")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List active behaviour rewards")
    public ApiResponse<List<TokenRewardDto>> listRewards() {
        return responses.ok(adminService.listRewards());
    }

    /**
     * Creates or supersedes the active reward for a behaviour.
     *
     * @param request the validated upsert request.
     * @return an envelope carrying the new active reward.
     */
    @PostMapping("/rewards")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create or supersede a behaviour reward")
    public ApiResponse<TokenRewardDto> upsertReward(
            @Valid @RequestBody UpsertTokenRewardRequest request) {
        return responses.ok(adminService.upsertReward(request));
    }

    /**
     * Retires (deactivates) a behaviour reward by public id.
     *
     * @param rewardId the reward's public id.
     * @return an empty success envelope.
     */
    @DeleteMapping("/rewards/{rewardId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Retire a behaviour reward (deactivate, never delete)")
    public ApiResponse<Void> deactivateReward(@PathVariable UUID rewardId) {
        adminService.deactivateReward(rewardId);
        return responses.ok(null);
    }
}
