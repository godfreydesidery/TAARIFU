package com.taarifu.common.security;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link RequiresTierAspect} — the MF-2 keystone (AUTH-DESIGN §7.2).
 *
 * <p>Responsibility: proves the interceptor gates on the <b>live</b> tier from {@link TierResolver} and
 * <b>ignores a forged/elevated {@code trustTier} claim</b>. The principal here carries a token claim of
 * {@code T3}; with a live tier below T3 the call is still blocked — exactly the privilege-escalation the
 * security review (MF-2) demanded be regression-tested. No Spring context / Docker needed.</p>
 */
@ExtendWith(MockitoExtension.class)
class RequiresTierAspectTest {

    @Mock
    private TierResolver tierResolver;
    @Mock
    private AuditEventService audit;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private MethodSignature signature;

    private final UUID caller = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Places a principal whose TOKEN claims T3 — the aspect must not trust this. */
    private void authenticateWithForgedT3Claim() {
        CurrentUser principal = new CurrentUser(caller, List.of("CITIZEN"), "T3"); // forged hint
        var auth = new UsernamePasswordAuthenticationToken(caller, null, List.of());
        auth.setDetails(principal);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @RequiresTier("T3")
    void t3Guarded() {
        // marker method carrying the annotation the aspect reads
    }

    private void wireJoinPointToT3Method() throws NoSuchMethodException {
        Method method = RequiresTierAspectTest.class.getDeclaredMethod("t3Guarded");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
    }

    @Test
    void forgedT3Claim_isIgnored_whenLiveTierIsBelowT3() throws Throwable {
        authenticateWithForgedT3Claim();
        wireJoinPointToT3Method();
        // LIVE tier is only T1 in the DB — the forged T3 token claim must not help.
        when(tierResolver.resolveLiveTierRank(caller)).thenReturn(1);

        RequiresTierAspect aspect = new RequiresTierAspect(tierResolver, audit);

        assertThatThrownBy(() -> aspect.enforceTier(joinPoint))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.TIER_TOO_LOW);

        // The guarded method never ran, and a tier-denied audit event was written.
        verify(joinPoint, never()).proceed();
        verify(audit).record(any(AuditEvent.class));
    }

    @Test
    void allowsAction_whenLiveTierMeetsRequirement() throws Throwable {
        authenticateWithForgedT3Claim();
        wireJoinPointToT3Method();
        when(tierResolver.resolveLiveTierRank(caller)).thenReturn(3); // live T3
        when(joinPoint.proceed()).thenReturn("ok");

        RequiresTierAspect aspect = new RequiresTierAspect(tierResolver, audit);

        assertThat(aspect.enforceTier(joinPoint)).isEqualTo("ok");
        verify(joinPoint).proceed();
    }

    @Test
    void blocks_whenUnauthenticated() throws NoSuchMethodException {
        wireJoinPointToT3Method();
        RequiresTierAspect aspect = new RequiresTierAspect(tierResolver, audit);

        assertThatThrownBy(() -> aspect.enforceTier(joinPoint))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }
}
