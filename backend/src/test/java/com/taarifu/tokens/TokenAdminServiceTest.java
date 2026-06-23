package com.taarifu.tokens;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.tokens.api.dto.UpsertActionCostPolicyRequest;
import com.taarifu.tokens.application.mapper.TokenMapper;
import com.taarifu.tokens.application.service.TokenAdminService;
import com.taarifu.tokens.domain.model.ActionCostPolicy;
import com.taarifu.tokens.domain.model.enums.QuotaPeriod;
import com.taarifu.tokens.domain.repository.ActionCostPolicyRepository;
import com.taarifu.tokens.domain.repository.TokenRewardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TokenAdminService} — admin config CRUD and the un-configurable-around fence
 * (PRD §23.4, D18).
 *
 * <p>Responsibility: proves (1) an admin cannot create a cost policy for a binding democratic action (the
 * fence is enforced at config time, not just at spend time), and (2) upserting a policy supersedes the prior
 * active row and bumps the version (versioned config, never mutate-in-place — PRD §23.4).</p>
 */
@ExtendWith(MockitoExtension.class)
class TokenAdminServiceTest {

    @Mock
    private ActionCostPolicyRepository policies;
    @Mock
    private TokenRewardRepository rewards;

    private TokenAdminService admin;

    @BeforeEach
    void setUp() {
        admin = new TokenAdminService(policies, rewards, new TokenMapper());
    }

    /** THE FENCE at config time: pricing a binding democratic action is FORBIDDEN and never persisted. */
    @Test
    void upsertPolicy_rejectsBindingDemocraticActions() {
        UpsertActionCostPolicyRequest req = new UpsertActionCostPolicyRequest(
                "SIGN_PETITION", "CITIZEN", 5, "DAILY", 0);

        assertThatThrownBy(() -> admin.upsertPolicy(req))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(policies, never()).save(any());
    }

    /** An invalid free-quota period name is a bad request (validated server-side). */
    @Test
    void upsertPolicy_rejectsInvalidPeriod() {
        UpsertActionCostPolicyRequest req = new UpsertActionCostPolicyRequest(
                "FILE_REPORT", "CITIZEN", 5, "FORTNIGHTLY", 3);

        assertThatThrownBy(() -> admin.upsertPolicy(req))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    /** Upserting over an existing active policy deactivates it and bumps the version (versioned config). */
    @Test
    void upsertPolicy_supersedesPriorActiveAndBumpsVersion() {
        ActionCostPolicy prior = new ActionCostPolicy("FILE_REPORT", "CITIZEN", 5, QuotaPeriod.DAILY, 3);
        prior.setPolicyVersion(2);
        when(policies.findByActionCodeAndRoleNameAndActiveTrue("FILE_REPORT", "CITIZEN"))
                .thenReturn(Optional.of(prior));
        when(policies.save(any(ActionCostPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        UpsertActionCostPolicyRequest req = new UpsertActionCostPolicyRequest(
                "FILE_REPORT", "CITIZEN", 8, "DAILY", 5);
        var dto = admin.upsertPolicy(req);

        assertThat(prior.isActive()).isFalse();          // prior superseded (deactivated, never deleted)
        assertThat(dto.policyVersion()).isEqualTo(3);     // version bumped 2 -> 3
        assertThat(dto.tokenCost()).isEqualTo(8);
        assertThat(dto.active()).isTrue();
    }
}
