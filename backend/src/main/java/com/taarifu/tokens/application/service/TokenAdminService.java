package com.taarifu.tokens.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.tokens.api.dto.ActionCostPolicyDto;
import com.taarifu.tokens.api.dto.TokenRewardDto;
import com.taarifu.tokens.api.dto.UpsertActionCostPolicyRequest;
import com.taarifu.tokens.api.dto.UpsertTokenRewardRequest;
import com.taarifu.tokens.application.mapper.TokenMapper;
import com.taarifu.tokens.domain.model.ActionCostPolicy;
import com.taarifu.tokens.domain.model.TokenReward;
import com.taarifu.tokens.domain.model.enums.QuotaPeriod;
import com.taarifu.tokens.domain.model.enums.RewardBehaviour;
import com.taarifu.tokens.domain.repository.ActionCostPolicyRepository;
import com.taarifu.tokens.domain.repository.TokenRewardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Application service for <b>admin token-economy configuration</b> — the cost/quota policies and behaviour
 * rewards (PRD §23.4 — "admin-tunable; versioned"; §23.5 transparency).
 *
 * <p>Responsibility: create/list/update the {@link ActionCostPolicy} and {@link TokenReward} catalogue.
 * Every endpoint backed by this service is {@code ROLE_ADMIN}-gated at the controller (deny-by-default,
 * ARCHITECTURE.md §6.2). Updates <b>supersede</b> rather than mutate-in-place: a changed policy deactivates
 * the prior active row and inserts a new {@link ActionCostPolicy#getPolicyVersion()} so historical metering
 * semantics are preserved and auditable (PRD §23.4 versioned, §23.5 transparency).</p>
 *
 * <p><b>Fence guard (D18):</b> a policy whose action code is a reserved binding democratic action (sign
 * petition / rate rep / binding poll) is rejected here — admins cannot configure a token cost that would
 * gate a democratic act, because those actions are never metered at all (PRD §23 fence). This makes the
 * fence un-configurable-around, not merely a convention.</p>
 */
@Service
@Transactional
public class TokenAdminService {

    private final ActionCostPolicyRepository policies;
    private final TokenRewardRepository rewards;
    private final TokenMapper mapper;

    /**
     * @param policies cost/quota policy persistence.
     * @param rewards  reward-config persistence.
     * @param mapper   entity→DTO mapper.
     */
    public TokenAdminService(ActionCostPolicyRepository policies,
                             TokenRewardRepository rewards,
                             TokenMapper mapper) {
        this.policies = policies;
        this.rewards = rewards;
        this.mapper = mapper;
    }

    // ---- ActionCostPolicy --------------------------------------------------------------------------

    /** @return all active cost/quota policies (admin listing). */
    @Transactional(readOnly = true)
    public List<ActionCostPolicyDto> listPolicies() {
        return policies.findByActiveTrue().stream().map(mapper::toPolicyDto).toList();
    }

    /**
     * Creates a policy, or supersedes the existing active one for the same {@code (actionCode, roleName)}.
     *
     * @param request the validated upsert request.
     * @return the new active policy DTO.
     * @throws ApiException {@link ErrorCode#FORBIDDEN} if the action code is a fenced binding action;
     *                      {@link ErrorCode#BAD_REQUEST} if the period name is invalid.
     */
    public ActionCostPolicyDto upsertPolicy(UpsertActionCostPolicyRequest request) {
        rejectFencedAction(request.actionCode());
        QuotaPeriod period = parsePeriod(request.freeQuotaPeriod());
        String roleName = blankToNull(request.roleName());

        // Supersede: deactivate the current active row for this key and bump the version (PRD §23.4 versioned).
        ActionCostPolicy prior = (roleName == null)
                ? policies.findActiveDefault(request.actionCode()).orElse(null)
                : policies.findByActionCodeAndRoleNameAndActiveTrue(request.actionCode(), roleName).orElse(null);
        int nextVersion = 1;
        if (prior != null) {
            prior.deactivate();
            policies.save(prior);
            nextVersion = prior.getPolicyVersion() + 1;
        }

        ActionCostPolicy created = new ActionCostPolicy(request.actionCode(), roleName,
                request.tokenCost(), period, request.freeQuotaCount());
        created.setPolicyVersion(nextVersion);
        return mapper.toPolicyDto(policies.save(created));
    }

    /**
     * Deactivates an active policy by public id (admin retire; never physical delete — config history).
     *
     * @param publicId the policy's public id.
     * @throws ResourceNotFoundException if no such active policy.
     */
    public void deactivatePolicy(UUID publicId) {
        ActionCostPolicy policy = policies.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("tokens.policy.notFound", publicId));
        policy.deactivate();
        policies.save(policy);
    }

    // ---- TokenReward -------------------------------------------------------------------------------

    /** @return all active reward configs (admin listing). */
    @Transactional(readOnly = true)
    public List<TokenRewardDto> listRewards() {
        return rewards.findByActiveTrue().stream().map(mapper::toRewardDto).toList();
    }

    /**
     * Creates a reward, or supersedes the existing active one for the same behaviour.
     *
     * @param request the validated upsert request.
     * @return the new active reward DTO.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if the behaviour/period name is invalid.
     */
    public TokenRewardDto upsertReward(UpsertTokenRewardRequest request) {
        RewardBehaviour behaviour = parseBehaviour(request.behaviour());
        QuotaPeriod capPeriod = parsePeriod(request.capPeriod());

        rewards.findByBehaviourAndActiveTrue(behaviour).ifPresent(prior -> {
            prior.deactivate();
            rewards.save(prior);
        });

        TokenReward created = new TokenReward(behaviour, request.grantAmount(), request.capCount(), capPeriod);
        return mapper.toRewardDto(rewards.save(created));
    }

    /**
     * Deactivates an active reward by public id (admin retire; never physical delete).
     *
     * @param publicId the reward's public id.
     * @throws ResourceNotFoundException if no such active reward.
     */
    public void deactivateReward(UUID publicId) {
        TokenReward reward = rewards.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("tokens.reward.notFound", publicId));
        reward.deactivate();
        rewards.save(reward);
    }

    // ---- internals ---------------------------------------------------------------------------------

    /** Rejects configuring a cost for a fenced binding democratic action (D18). */
    private void rejectFencedAction(String actionCode) {
        if (MeteringService.BINDING_ACTION_CODES.contains(actionCode)) {
            // Tokens may never meter democratic weight — refuse to even store such a policy (PRD §23 fence).
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    /** Parses a {@code QuotaPeriod} name, mapping a bad value to a validation error (localised). */
    private QuotaPeriod parsePeriod(String name) {
        try {
            return QuotaPeriod.valueOf(name);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
    }

    /** Parses a {@code RewardBehaviour} name, mapping a bad value to a validation error. */
    private RewardBehaviour parseBehaviour(String name) {
        try {
            return RewardBehaviour.valueOf(name);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
    }

    /** Treats a blank role name as the default (null) policy key. */
    private String blankToNull(String roleName) {
        return (roleName == null || roleName.isBlank()) ? null : roleName;
    }
}
