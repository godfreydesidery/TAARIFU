package com.taarifu.identity;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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

    @PersistenceContext private EntityManager em;

    @BeforeEach
    @Transactional
    void seedRolesAndCleanup() {
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

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> loginService.loginWithPassword(phone, "wrong"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHENTICATED);
        }
        // The 11th attempt is locked out (rate-limited), not just another auth failure (S-2).
        assertThatThrownBy(() -> loginService.loginWithPassword(phone, "wrong"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(auditCount(AuditEventType.AUTH_LOGIN_LOCKED)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void otpLogin_succeedsForExistingAccount() {
        String phone = "+255700000104";
        UUID signupChallenge = signupService.requestSignupOtp(phone);
        signupService.completeSignup(signupChallenge, requestAndReadCode(signupChallenge, phone));

        UUID loginChallenge = loginService.requestLoginOtp(phone);
        String loginCode = requestAndReadCode(loginChallenge, phone);
        TokenService.TokenPair pair = loginService.loginWithOtp(loginChallenge, loginCode);

        assertThat(pair.accessToken()).isNotBlank();
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

        // Re-present the now-consumed first refresh → reuse-detection → 403 + family revoked (S-3).
        assertThatThrownBy(() -> tokenService.rotate(firstRefresh))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // The NEW token (same family) is now revoked too — the family kill-switch fired.
        assertThatThrownBy(() -> tokenService.rotate(rotated.refreshToken()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);

        assertThat(auditCount(AuditEventType.AUTH_REFRESH_REUSE_DETECTED)).isGreaterThanOrEqualTo(1);
        assertThat(auditCount(AuditEventType.AUTH_FAMILY_REVOKED)).isGreaterThanOrEqualTo(1);
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
