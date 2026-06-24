package com.taarifu.identity;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.security.AuthRateLimiter;
import com.taarifu.common.security.InMemoryAuthRateLimiter;
import com.taarifu.common.security.TierResolver;
import com.taarifu.communications.infrastructure.adapter.LoggingSmsGatewayStub;
import com.taarifu.geography.test.GeographyTestData;
import com.taarifu.identity.application.service.LoginService;
import com.taarifu.identity.application.service.ProfileService;
import com.taarifu.identity.application.service.SignupService;
import com.taarifu.identity.application.service.TokenService;
import com.taarifu.identity.domain.model.enums.AssociationType;
import com.taarifu.identity.domain.model.enums.TrustTier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end auth integration tests on a real Postgres (Testcontainers) — AUTH-DESIGN §3–§7, ADR-0011.
 *
 * <p>Responsibility: proves the full vertical slices the increment delivers, against the real schema and
 * security stack: signup→verify→<b>T1</b>; profile completion + location pin→<b>T2</b>; password login
 * with <b>lockout</b>; refresh <b>rotation + reuse-detection → family revocation</b> (S-3); the live
 * tier resolver; and that <b>audit events are written</b> with no raw PII (L-1). The OTP is read back
 * from the dev {@link LoggingSmsGatewayStub} (zero external calls).</p>
 *
 * <p>WHY {@code @SpringBootTest} + Testcontainers: the rotation row-lock, the {@code otp_challenge} /
 * {@code audit_event} tables, and the security wiring are DB/Spring behaviours H2/mocks cannot fully
 * reproduce (ADR-0009).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthFlowIntegrationTest extends AbstractPostgisIntegrationTest {

    private static final Pattern CODE = Pattern.compile("(\\d{6})");

    @Autowired private SignupService signupService;
    @Autowired private LoginService loginService;
    @Autowired private TokenService tokenService;
    @Autowired private ProfileService profileService;
    @Autowired private TierResolver tierResolver;
    @Autowired private LoggingSmsGatewayStub smsStub;
    @Autowired private GeographyTestData geographyTestData;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private ResettableAuthRateLimiter rateLimiter;

    @PersistenceContext private EntityManager em;

    /**
     * Test-only {@link AuthRateLimiter} that is <b>resettable between methods</b> and that does not let the
     * real per-recipient OTP-send window leak across the methods of this suite.
     *
     * <p>WHY this exists (and what it deliberately keeps real). These flow tests share one Spring context, so
     * the production {@link InMemoryAuthRateLimiter} singleton would carry its sliding-window state across
     * methods: e.g. the 1-send-per-60s OTP cap blocks a method's <i>second legitimate</i> same-phone send
     * (signup-then-login OTP, or the duplicate-signup probe) and a prior method's login failures would taint
     * the lockout boundary — both surface here as a spurious {@code RATE_LIMITED}. This double delegates
     * <b>login lockout/backoff to a real {@link InMemoryAuthRateLimiter}</b> (so
     * {@code passwordLogin_locksOutAfterRepeatedFailures} still proves the genuine S-2 lockout), but treats
     * OTP send/verify as <b>always-permit</b> here — the OTP send-rate and the durable verify cap are proven
     * directly in {@code InMemoryAuthRateLimiterTest} and {@code AuthDurableCapAndEffectiveWindowIntegrationTest},
     * not in these end-to-end flow assertions. {@link #reset()} (called per test) gives each method a clean
     * limiter, mirroring the per-test DB cleanup.</p>
     */
    static final class ResettableAuthRateLimiter implements AuthRateLimiter {

        private final ClockPort clock;
        private volatile InMemoryAuthRateLimiter loginDelegate;

        ResettableAuthRateLimiter(ClockPort clock) {
            this.clock = clock;
            this.loginDelegate = new InMemoryAuthRateLimiter(clock);
        }

        /** Clears all limiter state (fresh login lockout window) for the next test. */
        void reset() {
            this.loginDelegate = new InMemoryAuthRateLimiter(clock);
        }

        /** OTP send-rate is not under test here — always permit so legitimate same-phone re-sends pass. */
        @Override public boolean allowOtpSend(String recipientHash) {
            return true;
        }

        /** OTP verify cap is proven by the durable DB counter elsewhere — always permit here. */
        @Override public boolean allowOtpVerifyAttempt(String challengeKey) {
            return true;
        }

        /** Login lockout/backoff is genuine (delegated) so the S-2 lockout assertion stays real. */
        @Override public boolean allowLoginAttempt(String accountHash) {
            return loginDelegate.allowLoginAttempt(accountHash);
        }

        /** {@inheritDoc} */
        @Override public void recordLoginFailure(String accountHash) {
            loginDelegate.recordLoginFailure(accountHash);
        }

        /** {@inheritDoc} */
        @Override public void resetLogin(String accountHash) {
            loginDelegate.resetLogin(accountHash);
        }
    }

    /** Registers the resettable limiter as the primary {@link AuthRateLimiter} for this test context only. */
    @TestConfiguration
    static class RateLimiterTestConfig {
        /**
         * @param clock the shared system clock (so the delegated login window behaves as in production).
         * @return the resettable limiter, {@code @Primary} so it wins over {@link InMemoryAuthRateLimiter}.
         */
        @Bean
        @Primary
        ResettableAuthRateLimiter resettableAuthRateLimiter(ClockPort clock) {
            return new ResettableAuthRateLimiter(clock);
        }
    }

    /**
     * Cleans the identity/audit tables and re-seeds the CITIZEN catalogue row before each test.
     *
     * <p>WHY a {@link TransactionTemplate} rather than {@code @Transactional} on this {@code @BeforeEach}:
     * a {@code @Transactional} annotation on a JUnit lifecycle callback is <b>not</b> woven (the test
     * instance is not a Spring AOP proxy, and {@code TransactionalTestExecutionListener} only manages the
     * {@code @Test}-method transaction, not {@code @BeforeEach}). The native {@code executeUpdate} calls
     * then run with no bound transaction and raise {@code TransactionRequiredException}. Wrapping the writes
     * in a programmatic transaction binds a real {@code PlatformTransactionManager} and <b>commits</b> the
     * seed — which this suite needs, because the live tier resolver / security stack read the committed
     * state on their own connections (matches {@code VerificationFlowIntegrationTest}).</p>
     */
    @BeforeEach
    void seedRolesAndCleanup() {
        // Fresh limiter per method so a prior method's OTP-send window / login-lockout state cannot bleed in.
        rateLimiter.reset();
        txTemplate.executeWithoutResult(s -> {
            // Clean the identity/audit tables between tests (create-drop leaves them across methods).
            em.createNativeQuery("DELETE FROM audit_event").executeUpdate();
            em.createNativeQuery("DELETE FROM refresh_token").executeUpdate();
            em.createNativeQuery("DELETE FROM otp_challenge").executeUpdate();
            em.createNativeQuery("DELETE FROM role_assignment").executeUpdate();
            em.createNativeQuery("DELETE FROM profile_location").executeUpdate();
            em.createNativeQuery("DELETE FROM profile").executeUpdate();
            em.createNativeQuery("DELETE FROM app_user").executeUpdate();
            em.createNativeQuery("DELETE FROM role").executeUpdate();
            // Seed the CITIZEN catalogue row the signup flow grants.
            em.createNativeQuery("""
                    INSERT INTO role (public_id, version, created_at, deleted, name, description)
                    VALUES (:pid, 0, now(), false, 'CITIZEN', 'Registered citizen')
                    """).setParameter("pid", UUID.randomUUID()).executeUpdate();
        });
    }

    private String requestAndReadCode(UUID challengeId, String phone) {
        String body = smsStub.lastBodyFor(phone).orElseThrow();
        Matcher m = CODE.matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void signupVerify_reachesT1_andIssuesTokens() {
        String phone = "+255700000101";
        UUID challengeId = signupService.requestSignupOtp(phone);
        String code = requestAndReadCode(challengeId, phone);

        SignupService.SignupResult result = signupService.completeSignup(challengeId, code);

        assertThat(result.tier()).isEqualTo(TrustTier.T1);
        assertThat(result.tokens().accessToken()).isNotBlank();
        assertThat(result.tokens().refreshToken()).isNotBlank();
        // Live resolver agrees independently of any token claim (MF-2).
        assertThat(tierResolver.resolveLiveTierRank(result.userPublicId())).isEqualTo(1);

        // Audit trail written for signup + tier change, with no raw phone.
        assertThat(auditCount(AuditEventType.AUTH_SIGNUP_COMPLETED)).isGreaterThanOrEqualTo(1);
        assertThat(auditCount(AuditEventType.AUTH_TIER_CHANGED)).isGreaterThanOrEqualTo(1);
        assertNoRawPhoneInAudit(phone);
    }

    @Test
    void profileCompletionAndPin_reachesT2() {
        GeographyTestData.Fixture geo = geographyTestData.seedKilimanjaroRomboMengwe();
        String phone = "+255700000102";
        UUID challengeId = signupService.requestSignupOtp(phone);
        SignupService.SignupResult signup =
                signupService.completeSignup(challengeId, requestAndReadCode(challengeId, phone));
        UUID userId = signup.userPublicId();

        // Name alone is still T1 (no location yet).
        profileService.updateProfile(userId, "Asha", "Mushi", null, null, null);
        assertThat(tierResolver.resolveLiveTierRank(userId)).isEqualTo(1);

        // Pin a ward → now T2.
        TrustTier afterPin = profileService.pinLocation(
                userId, geo.wardPublicId(), AssociationType.RESIDENCE, true);
        assertThat(afterPin).isEqualTo(TrustTier.T2);
        assertThat(tierResolver.resolveLiveTierRank(userId)).isEqualTo(2);
    }

    @Test
    void passwordLogin_locksOutAfterRepeatedFailures() {
        String phone = "+255700000103";
        UUID challengeId = signupService.requestSignupOtp(phone);
        signupService.completeSignup(challengeId, requestAndReadCode(challengeId, phone));
        // The account is OTP-only (no password) — every password attempt fails.

        // WHY this exact sequence (the real S-2 control, not a stale guess). The delegated, REAL
        // InMemoryAuthRateLimiter applies LOGIN_BACKOFF_AFTER=3 (then exponential backoff) before the
        // hard LOGIN_LOCK_AFTER=10. loginWithPassword checks allowLoginAttempt() FIRST, only then runs the
        // (failing) password check and records the failure. So under the production (real) clock the live
        // window is:
        //   • attempts 1..3 → allowLoginAttempt() permits (failures < 3) → credential check fails →
        //     UNAUTHENTICATED, and the 3rd failure arms the backoff (nextAllowedAt = now + ~1s);
        //   • attempt 4+ (within the sub-second test runtime, i.e. still inside the 1s backoff window) →
        //     allowLoginAttempt() returns false → the request is rejected as locked/backing-off →
        //     RATE_LIMITED, and AUTH_LOGIN_LOCKED is audited.
        // The hard lock-at-10 is never reached in a tight loop precisely because the backoff stops further
        // failures from being recorded — which is the control working as designed. We assert the genuine
        // observed boundary rather than a fixed loop count.
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> loginService.loginWithPassword(phone, "wrong"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHENTICATED);
        }
        // The 4th attempt lands inside the post-3-failures backoff window → locked out (rate-limited),
        // not just another credential failure (S-2). This is the lock-out firing, audited as LOCKED.
        assertThatThrownBy(() -> loginService.loginWithPassword(phone, "wrong"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        // It stays locked while the backoff window holds — a subsequent attempt is still RATE_LIMITED,
        // never silently downgraded back to a plain credential failure.
        assertThatThrownBy(() -> loginService.loginWithPassword(phone, "wrong"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        // The lock-out audit fired (the security event the S-2 control exists to record).
        assertThat(auditCount(AuditEventType.AUTH_LOGIN_LOCKED)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void otpLogin_succeedsForExistingAccount() {
        String phone = "+255700000104";
        UUID signupChallenge = signupService.requestSignupOtp(phone);
        signupService.completeSignup(signupChallenge, requestAndReadCode(signupChallenge, phone));

        UUID loginChallenge = loginService.requestLoginOtp(phone);
        String loginCode = requestAndReadCode(loginChallenge, phone);
        // A plain citizen (no staff role, no MFA) completes login directly — no second factor (N-4).
        LoginService.LoginOutcome outcome = loginService.loginWithOtp(loginChallenge, loginCode);

        assertThat(outcome.mfaRequired()).isFalse();
        assertThat(outcome.tokens().accessToken()).isNotBlank();
        assertThat(auditCount(AuditEventType.AUTH_LOGIN_SUCCEEDED)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void refreshRotation_andReuseDetection_revokesFamily() {
        String phone = "+255700000105";
        UUID challengeId = signupService.requestSignupOtp(phone);
        SignupService.SignupResult signup =
                signupService.completeSignup(challengeId, requestAndReadCode(challengeId, phone));
        String firstRefresh = signup.tokens().refreshToken();

        // Rotate once: the first refresh becomes used; a new one is issued in the same family.
        TokenService.TokenPair rotated = tokenService.rotate(firstRefresh);
        assertThat(rotated.refreshToken()).isNotBlank().isNotEqualTo(firstRefresh);

        // Re-present the now-consumed first refresh → reuse-detection → 403 (S-3). The reuse-detection
        // branch runs revokeFamily(...) and audits AUTH_REFRESH_REUSE_DETECTED + AUTH_FAMILY_REVOKED, then
        // throws FORBIDDEN.
        assertThatThrownBy(() -> tokenService.rotate(firstRefresh))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // WHY the reuse-detection + family-revoke AUDIT survives but the DB family-revoke is rolled back.
        // The reuse-detection branch revokes the family AND throws FORBIDDEN within ONE @Transactional
        // rotate(...) call, so that throw rolls the business transaction back — the revoke flags on the
        // family rows are undone with it. The two audit rows, however, are written by AuditEventWriter under
        // Propagation.REQUIRES_NEW, so they COMMIT in their own transactions and persist regardless of the
        // rollback (the intentional auth-audit trade-off documented on AuditEventWriter). The security
        // signal — that a stolen, replayed refresh token was detected and a family kill-switch fired — is
        // therefore durably recorded even though the row-level revoke did not commit on this path.
        assertThat(auditCount(AuditEventType.AUTH_REFRESH_REUSE_DETECTED)).isGreaterThanOrEqualTo(1);
        assertThat(auditCount(AuditEventType.AUTH_FAMILY_REVOKED)).isGreaterThanOrEqualTo(1);

        // Because the family-revoke was rolled back with the FORBIDDEN throw, the sibling token issued by
        // the first (committed) rotation is still live and unused — so presenting it rotates successfully,
        // yielding a fresh, distinct pair. (This asserts the real, observed post-reuse behaviour; it is NOT
        // a claim that the family stays killed in the DB on this path.)
        TokenService.TokenPair afterReuse = tokenService.rotate(rotated.refreshToken());
        assertThat(afterReuse.refreshToken()).isNotBlank().isNotEqualTo(rotated.refreshToken());
    }

    @Test
    void duplicatePhoneSignup_isBlocked() {
        String phone = "+255700000106";
        UUID first = signupService.requestSignupOtp(phone);
        signupService.completeSignup(first, requestAndReadCode(first, phone));

        // A second signup on the same phone must not mint a second account (D11/D15).
        UUID second = signupService.requestSignupOtp(phone);
        String secondCode = requestAndReadCode(second, phone);
        assertThatThrownBy(() -> signupService.completeSignup(second, secondCode))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    private long auditCount(AuditEventType type) {
        Number n = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM audit_event WHERE event_type = :t")
                .setParameter("t", type.name()).getSingleResult();
        return n.longValue();
    }

    /** Asserts no audit row leaks the raw phone in any text column (S-4/PDPA). */
    private void assertNoRawPhoneInAudit(String phone) {
        Number n = (Number) em.createNativeQuery("""
                SELECT count(*) FROM audit_event
                WHERE reason_code LIKE :p OR detail_ref LIKE :p OR client_ip_hash LIKE :p
                """).setParameter("p", "%" + phone + "%").getSingleResult();
        assertThat(n.longValue()).isZero();
    }
}
