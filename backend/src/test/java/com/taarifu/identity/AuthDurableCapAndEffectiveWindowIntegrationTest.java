package com.taarifu.identity;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.AuthRateLimiter;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.JwtService;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.common.security.TokenType;
import com.taarifu.communications.infrastructure.adapter.LoggingSmsGatewayStub;
import com.taarifu.identity.application.service.OtpService;
import com.taarifu.identity.application.service.TokenService;
import com.taarifu.identity.domain.model.Role;
import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.OtpPurpose;
import com.taarifu.identity.domain.model.enums.RoleName;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import com.taarifu.identity.domain.repository.RoleRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the two AUTH-increment review must-fixes against a real Postgres (Testcontainers):
 * <b>N-1</b> the OTP failed-attempt DB counter is now durable, and <b>N-2</b> scope/role authorization
 * honours a {@code RoleAssignment}'s effective window.
 *
 * <p><b>N-1 (P2)</b> — {@link #fiveWrongCodes_burnChallenge_byDbCounter_independentOfInMemoryLimiter()}
 * disables the in-memory verify-cap (via a stub {@link AuthRateLimiter} that always allows) so the test
 * proves the <b>DB</b> {@code otp_challenge.attempts} counter alone burns the challenge: after five wrong
 * codes the counter reads 5 and even the <b>correct</b> code is then rejected — which can only be the DB
 * attempt-cap firing, since the in-memory limiter has been removed from the path.</p>
 *
 * <p><b>N-2 (P2)</b> — {@link #lapsedOrFutureGrant_isDeniedByScopeGuard_andNotEmittedAsRoleAuthority()}
 * grants a role that is {@link RoleStatus#ACTIVE} but outside its {@code effectiveFrom}/{@code effectiveTo}
 * window (one lapsed, one future) and asserts the {@link ScopeGuard} denies it <b>and</b>
 * {@link TokenService} does not stamp it as a role claim — while an in-window ACTIVE grant is allowed and
 * emitted, proving the window (not just {@code status}) gates authorization.</p>
 *
 * <p>WHY a dedicated class (not added to {@code AuthFlowIntegrationTest}): N-1 must run with the in-memory
 * rate limiter neutralised to isolate the DB cap, which requires a context-level {@link AuthRateLimiter}
 * override; keeping that in its own context avoids weakening the anti-automation assertions in the main
 * auth flow suite. WHY Testcontainers: {@code create-drop} flush of the {@code attempts} increment and the
 * JPQL effective-window predicate are DB behaviours mocks cannot reproduce (ADR-0009).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthDurableCapAndEffectiveWindowIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired private OtpService otpService;
    @Autowired private TokenService tokenService;
    @Autowired private ScopeGuard scopeGuard;
    @Autowired private JwtService jwtService;
    @Autowired private LoggingSmsGatewayStub smsStub;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RoleAssignmentRepository roleAssignmentRepository;
    @Autowired private TransactionTemplate txTemplate;

    @PersistenceContext private EntityManager em;

    /**
     * Test-only configuration that replaces the {@link AuthRateLimiter} with an always-permit stub.
     *
     * <p>N-1: removing the in-memory verify-cap from the path is what lets this suite prove the
     * <b>durable DB counter</b> is the control that burns the challenge — not the (non-durable,
     * single-instance) in-memory limiter the review flagged. {@code @Primary} wins over the
     * {@code InMemoryAuthRateLimiter} default for this context only.</p>
     */
    @TestConfiguration
    static class AlwaysAllowRateLimiterConfig {
        /** @return an {@link AuthRateLimiter} that permits every action (in-memory cap neutralised). */
        @Bean
        @Primary
        AuthRateLimiter alwaysAllowRateLimiter() {
            return new AuthRateLimiter() {
                @Override public boolean allowOtpSend(String recipientHash) {
                    return true;
                }
                @Override public boolean allowOtpVerifyAttempt(String challengeKey) {
                    return true;
                }
                @Override public boolean allowLoginAttempt(String accountHash) {
                    return true;
                }
                @Override public void recordLoginFailure(String accountHash) {
                    // no-op
                }
                @Override public void resetLogin(String accountHash) {
                    // no-op
                }
            };
        }
    }

    /**
     * Cleans the identity/audit tables and re-seeds the role catalogue before each test.
     *
     * <p>WHY a {@link TransactionTemplate} rather than {@code @Transactional} on this {@code @BeforeEach}:
     * the annotation is not woven on a JUnit lifecycle callback (no AOP proxy; the test transaction listener
     * only manages {@code @Test}), so the native {@code executeUpdate} calls would raise
     * {@code TransactionRequiredException}. The programmatic transaction binds a manager and commits the
     * seed so the services/security stack read it on their own connections.</p>
     */
    @BeforeEach
    void cleanAndSeedRoles() {
        txTemplate.executeWithoutResult(s -> {
            // create-drop keeps rows across methods; clean the identity/audit tables between tests.
            em.createNativeQuery("DELETE FROM audit_event").executeUpdate();
            em.createNativeQuery("DELETE FROM refresh_token").executeUpdate();
            em.createNativeQuery("DELETE FROM otp_challenge").executeUpdate();
            em.createNativeQuery("DELETE FROM role_assignment_area").executeUpdate();
            em.createNativeQuery("DELETE FROM role_assignment_category").executeUpdate();
            em.createNativeQuery("DELETE FROM role_assignment").executeUpdate();
            em.createNativeQuery("DELETE FROM profile_location").executeUpdate();
            em.createNativeQuery("DELETE FROM profile").executeUpdate();
            em.createNativeQuery("DELETE FROM app_user").executeUpdate();
            em.createNativeQuery("DELETE FROM role").executeUpdate();
            // Seed the role-catalogue rows the flows below grant/assign.
            seedRole(RoleName.CITIZEN, "Registered citizen");
            seedRole(RoleName.RESPONDER_AGENT, "Responder agent (scoped)");
        });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------------------------------------
    // N-1 — durable OTP failed-attempt counter
    // ------------------------------------------------------------------------------------------------

    /**
     * N-1: five wrong codes must burn the challenge <b>by the persisted DB counter</b>, independently of
     * the in-memory limiter (which is neutralised here). Proof: after five wrong attempts the stored
     * {@code attempts} equals the cap (5), and a sixth attempt with the <b>correct</b> code is rejected —
     * only the DB attempt-cap in {@code OtpChallenge.isVerifiable} can reject a correct code here.
     */
    @Test
    void fiveWrongCodes_burnChallenge_byDbCounter_independentOfInMemoryLimiter() {
        String phone = "+255700000301";
        // Issue a real challenge so the code hash is set exactly as production does.
        UUID challengeId = otpService.issueSms(phone, OtpPurpose.LOGIN, null);
        String correctCode = readCode(phone);

        // Five wrong codes — each must commit the increment despite throwing (the N-1 fix).
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> otpService.verify(challengeId, "000000", OtpPurpose.LOGIN))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        // The DURABLE DB counter advanced to the cap (would be 0 if the increment had rolled back).
        assertThat(persistedAttempts(challengeId)).isEqualTo(5);

        // Now the CORRECT code is still rejected — only the DB attempt-cap (isVerifiable) can do this.
        assertThatThrownBy(() -> otpService.verify(challengeId, correctCode, OtpPurpose.LOGIN))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        // The challenge was never consumed (success path never ran) yet is unusable — burned by the counter.
        assertThat(em.createNativeQuery(
                        "SELECT consumed FROM otp_challenge WHERE public_id = :pid")
                .setParameter("pid", challengeId).getSingleResult())
                .isEqualTo(Boolean.FALSE);
    }

    // ------------------------------------------------------------------------------------------------
    // N-2 — effective-window-aware scope/role authorization
    // ------------------------------------------------------------------------------------------------

    /**
     * N-2: a grant that is {@link RoleStatus#ACTIVE} but outside its effective window must NOT authorize.
     * Asserts both authorization surfaces honour the window via the one shared
     * {@code findActiveEffectiveByUser} query: the {@link ScopeGuard} denies a lapsed/future grant's
     * scope, and {@link TokenService} omits its role from the access-token claim — while an in-window
     * ACTIVE grant is allowed and emitted (the positive control).
     */
    @Test
    void lapsedOrFutureGrant_isDeniedByScopeGuard_andNotEmittedAsRoleAuthority() {
        Instant now = Instant.now();
        UUID areaId = UUID.randomUUID();

        // --- Subject A: a RESPONDER_AGENT grant that LAPSED (effective_to in the past) ---
        User lapsedUser = persistActiveUser("+255700000302");
        persistResponderGrant(lapsedUser, areaId,
                now.minus(10, ChronoUnit.DAYS),   // effectiveFrom
                now.minus(1, ChronoUnit.DAYS));   // effectiveTo (past → lapsed)

        authenticateAs(lapsedUser.getPublicId());
        assertThat(scopeGuard.canActOnArea(areaId)).isFalse();
        assertThat(rolesInAccessTokenFor(lapsedUser)).doesNotContain(RoleName.RESPONDER_AGENT.name());

        // --- Subject B: a RESPONDER_AGENT grant that is NOT YET EFFECTIVE (effective_from in the future) ---
        User futureUser = persistActiveUser("+255700000303");
        persistResponderGrant(futureUser, areaId,
                now.plus(1, ChronoUnit.DAYS),     // effectiveFrom (future → not yet effective)
                now.plus(10, ChronoUnit.DAYS));   // effectiveTo

        authenticateAs(futureUser.getPublicId());
        assertThat(scopeGuard.canActOnArea(areaId)).isFalse();
        assertThat(rolesInAccessTokenFor(futureUser)).doesNotContain(RoleName.RESPONDER_AGENT.name());

        // --- Positive control: an IN-WINDOW ACTIVE grant authorizes and is emitted ---
        User activeUser = persistActiveUser("+255700000304");
        persistResponderGrant(activeUser, areaId,
                now.minus(1, ChronoUnit.DAYS),    // effectiveFrom (past)
                now.plus(1, ChronoUnit.DAYS));    // effectiveTo (future) → currently effective

        authenticateAs(activeUser.getPublicId());
        assertThat(scopeGuard.canActOnArea(areaId)).isTrue();
        assertThat(rolesInAccessTokenFor(activeUser)).contains(RoleName.RESPONDER_AGENT.name());
    }

    // ------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------

    /** Seeds a role-catalogue row by SQL (mirrors the other auth integration tests). */
    private void seedRole(RoleName name, String description) {
        em.createNativeQuery("""
                INSERT INTO role (public_id, version, created_at, deleted, name, description)
                VALUES (:pid, 0, now(), false, :name, :desc)
                """)
                .setParameter("pid", UUID.randomUUID())
                .setParameter("name", name.name())
                .setParameter("desc", description)
                .executeUpdate();
    }

    /** Reads the 6-digit code from the dev SMS stub (zero external calls). */
    private String readCode(String phone) {
        String body = smsStub.lastBodyFor(phone).orElseThrow();
        var m = java.util.regex.Pattern.compile("(\\d{6})").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** @return the persisted {@code attempts} for a challenge, read straight from the DB (the N-1 proof). */
    private int persistedAttempts(UUID challengeId) {
        Number n = (Number) em.createNativeQuery(
                        "SELECT attempts FROM otp_challenge WHERE public_id = :pid")
                .setParameter("pid", challengeId).getSingleResult();
        return n.intValue();
    }

    /** Persists an active account directly (no OTP flow needed for the N-2 scope scenarios). */
    private User persistActiveUser(String phone) {
        User user = User.createPending(phone);
        user.activate();
        return userRepository.saveAndFlush(user);
    }

    /**
     * Grants RESPONDER_AGENT to a user scoped to one area, with an explicit effective window — persisted
     * in one repository write (its own transaction) so no native writes run outside a transaction.
     *
     * <p>WHY reflection sets the window + area: {@code RoleAssignment.grant(...)} deliberately exposes no
     * {@code effectiveFrom}/{@code effectiveTo} or area mutators (the granting module owns those); the test
     * populates the transient entity directly so the persisted row drives the JPQL window predicate
     * exactly as in production. Status stays {@link RoleStatus#ACTIVE} so ONLY the window differs across
     * subjects — isolating the N-2 fix.</p>
     */
    private void persistResponderGrant(User user, UUID areaId, Instant effectiveFrom, Instant effectiveTo) {
        Role responder = roleRepository.findByName(RoleName.RESPONDER_AGENT).orElseThrow();
        RoleAssignment grant = RoleAssignment.grant(user, responder, RoleStatus.ACTIVE);
        setField(grant, "effectiveFrom", effectiveFrom);
        setField(grant, "effectiveTo", effectiveTo);
        addArea(grant, areaId);
        roleAssignmentRepository.saveAndFlush(grant);
    }

    /** Reflectively sets a private field on an entity (the window fields have no setters by design). */
    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = RoleAssignment.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reflectively adds an area id to the grant's scope set (no public mutator by design). */
    @SuppressWarnings("unchecked")
    private static void addArea(RoleAssignment grant, UUID areaId) {
        try {
            var field = RoleAssignment.class.getDeclaredField("areaIds");
            field.setAccessible(true);
            ((java.util.Set<UUID>) field.get(grant)).add(areaId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Places {@code userPublicId} in the security context so {@link CurrentUser#current()} resolves it. */
    private void authenticateAs(UUID userPublicId) {
        SecurityContextHolder.clearContext();
        CurrentUser principal = new CurrentUser(userPublicId, List.of(), null);
        var auth = new UsernamePasswordAuthenticationToken(userPublicId, null, List.of());
        auth.setDetails(principal);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** Issues a real access token for the user and returns the role names it actually carries. */
    private List<String> rolesInAccessTokenFor(User user) {
        TokenService.TokenPair pair = tokenService.issuePair(user);
        return jwtService.verify(pair.accessToken(), TokenType.ACCESS).roles();
    }
}
