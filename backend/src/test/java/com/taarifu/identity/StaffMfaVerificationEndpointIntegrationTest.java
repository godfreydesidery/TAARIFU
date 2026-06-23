package com.taarifu.identity;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.security.JwtService;
import com.taarifu.communications.infrastructure.adapter.LoggingSmsGatewayStub;
import com.taarifu.geography.test.GeographyTestData;
import com.taarifu.identity.application.service.LocationService;
import com.taarifu.identity.application.service.LoginService;
import com.taarifu.identity.application.service.ProfileService;
import com.taarifu.identity.application.service.SignupService;
import com.taarifu.identity.application.service.TotpService;
import com.taarifu.identity.application.service.VerificationService;
import com.taarifu.identity.domain.model.enums.AssociationType;
import com.taarifu.identity.domain.model.enums.IdType;
import com.taarifu.identity.infrastructure.totp.TotpGenerator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * N-4 + D16 end-to-end: the first scoped staff endpoint cannot be reached or used without staff TOTP, and
 * a Moderator cannot approve their own verification (VERIFICATION-DESIGN §5/§7; D16).
 *
 * <p>Proves, against the real security stack on Postgres:</p>
 * <ul>
 *   <li>a staff (MODERATOR) account that has <b>not</b> enrolled TOTP cannot complete login — password
 *       login returns {@code MFA_REQUIRED} (no token pair, N-4);</li>
 *   <li>after enrolment, login returns {@code mfaRequired=true} + an {@code mfaToken}, and only the TOTP
 *       step issues the real pair;</li>
 *   <li>a citizen (non-MFA) access token cannot reach {@code GET /moderation/verifications} (403 — the
 *       endpoint gate {@code @mfa.isStaffMfaSatisfied()} denies a non-staff/non-MFA session);</li>
 *   <li>a Moderator approving their <b>own</b> pending request is blocked (D16, 403).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffMfaVerificationEndpointIntegrationTest extends AbstractPostgisIntegrationTest {

    private static final Pattern CODE = Pattern.compile("(\\d{6})");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private SignupService signupService;
    @Autowired private ProfileService profileService;
    @Autowired private LocationService locationService;
    @Autowired private VerificationService verificationService;
    @Autowired private LoginService loginService;
    @Autowired private TotpService totpService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private LoggingSmsGatewayStub smsStub;
    @Autowired private GeographyTestData geographyTestData;
    @Autowired private ClockPort clock;
    @Autowired private TransactionTemplate txTemplate;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    void seed() {
        txTemplate.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM audit_event").executeUpdate();
            em.createNativeQuery("DELETE FROM refresh_token").executeUpdate();
            em.createNativeQuery("DELETE FROM otp_challenge").executeUpdate();
            em.createNativeQuery("DELETE FROM verification_request").executeUpdate();
            em.createNativeQuery("DELETE FROM role_assignment").executeUpdate();
            em.createNativeQuery("DELETE FROM profile_location").executeUpdate();
            em.createNativeQuery("DELETE FROM profile").executeUpdate();
            em.createNativeQuery("DELETE FROM app_user").executeUpdate();
            em.createNativeQuery("DELETE FROM role").executeUpdate();
        });
        geographyTestData.clear();
        txTemplate.executeWithoutResult(s -> {
            seedRole("CITIZEN");
            seedRole("MODERATOR");
        });
    }

    @Test
    void staffLoginWithoutTotpEnrolment_isRejected() {
        UUID moderator = signup("+255700000401");
        setPassword(moderator, "Str0ngPass!");
        grantModerator(moderator);

        // N-4: a staff account with no TOTP cannot complete login — MFA_REQUIRED, not a token pair.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> loginService.loginWithPassword("+255700000401", "Str0ngPass!"))
                .isInstanceOf(com.taarifu.common.error.ApiException.class)
                .extracting(e -> ((com.taarifu.common.error.ApiException) e).getErrorCode())
                .isEqualTo(com.taarifu.common.error.ErrorCode.MFA_REQUIRED);
    }

    @Test
    void staffLoginWithTotp_completesViaSecondFactor() {
        UUID moderator = signup("+255700000402");
        setPassword(moderator, "Str0ngPass!");
        grantModerator(moderator);
        String secret = enrolTotp(moderator);

        // First factor: returns an MFA challenge (NOT a token pair) for a staff account (N-4).
        LoginService.LoginOutcome outcome = loginService.loginWithPassword("+255700000402", "Str0ngPass!");
        assertThat(outcome.mfaRequired()).isTrue();
        assertThat(outcome.tokens()).isNull();
        assertThat(outcome.mfaToken()).isNotBlank();

        // Second factor: the TOTP code exchanges the challenge for the real pair.
        String totp = new TotpGenerator(30).codeAt(secret, clock.now().getEpochSecond());
        var pair = loginService.completeTotpLogin(outcome.mfaToken(), totp);
        assertThat(pair.accessToken()).isNotBlank();
    }

    @Test
    void citizenSession_cannotReachModerationQueue() throws Exception {
        UUID citizen = signup("+255700000403");
        // An honestly-issued CITIZEN access token (no staff role, no MFA) is denied the staff surface.
        String token = jwtService.issueAccessToken(citizen, List.of("CITIZEN"), "T1");
        mockMvc.perform(get("/api/v1/moderation/verifications").contextPath("/api/v1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void moderatorWithForgedRoleButNoMfa_cannotReachModerationQueue() throws Exception {
        UUID account = signup("+255700000404");
        grantModerator(account); // holds the role, but NEVER enrolled TOTP
        // Even with a MODERATOR role claim, the endpoint gate @mfa.isStaffMfaSatisfied() denies (N-4):
        // the account has mfaEnabled=false, so the staff surface stays closed.
        String token = jwtService.issueAccessToken(account, List.of("MODERATOR"), "T1");
        mockMvc.perform(get("/api/v1/moderation/verifications").contextPath("/api/v1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void moderatorCannotApproveOwnRequest_d16() throws Exception {
        GeographyTestData.Fixture geo = geographyTestData.seedKilimanjaroRomboMengwe();
        // The moderator is ALSO a citizen who submitted their own ID (multi-hat). D16 must block self-approval.
        UUID moderator = t2Citizen("+255700000405", geo);
        grantModerator(moderator);
        String secret = enrolTotp(moderator);
        VerificationService.SubmitResult own = verificationService.submitIdVerification(
                moderator, IdType.NATIONAL, "NIDA-SELF-1", "Asha Mushi", "obj/ev");

        // Mint a staff token AND mark MFA enabled so @mfa.isStaffMfaSatisfied() is true — the ONLY thing
        // left to stop the action is the conflict-of-interest guard (D16).
        String staffToken = jwtService.issueAccessToken(moderator, List.of("MODERATOR"), "T2");
        mockMvc.perform(post("/api/v1/moderation/verifications/{id}/approve", own.verificationPublicId())
                        .contextPath("/api/v1")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.statusCode").value(403));

        // The self-approval did NOT lift the citizen to T3 (idVerified still false).
        Number verified = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM profile p JOIN app_user u ON u.id = p.user_id "
                        + "WHERE u.public_id = :uid AND p.id_verified = true")
                .setParameter("uid", moderator).getSingleResult();
        assertThat(verified.longValue()).isZero();
        // secret was used to enrol; reference it so the variable is not flagged unused.
        assertThat(secret).isNotBlank();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private UUID signup(String phone) {
        UUID challengeId = signupService.requestSignupOtp(phone);
        return signupService.completeSignup(challengeId, readCode(phone)).userPublicId();
    }

    private UUID t2Citizen(String phone, GeographyTestData.Fixture geo) {
        UUID userId = signup(phone);
        profileService.updateProfile(userId, "Asha", "Mushi", null, null, null);
        locationService.addLocation(userId, geo.wardPublicId(), AssociationType.RESIDENCE, true);
        return userId;
    }

    private String readCode(String recipient) {
        Matcher m = CODE.matcher(smsStub.lastBodyFor(recipient).orElseThrow());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** Enrols TOTP for an account by driving setup+activate, returning the raw secret for code generation. */
    private String enrolTotp(UUID userPublicId) {
        TotpService.TotpEnrolment enrolment = totpService.setup(userPublicId);
        String code = new TotpGenerator(30).codeAt(enrolment.secret(), clock.now().getEpochSecond());
        totpService.activate(userPublicId, code);
        return enrolment.secret();
    }

    void setPassword(UUID userPublicId, String raw) {
        String hash = passwordEncoder.encode(raw);
        txTemplate.executeWithoutResult(s ->
                em.createNativeQuery("UPDATE app_user SET password_hash = :h WHERE public_id = :pid")
                        .setParameter("h", hash).setParameter("pid", userPublicId).executeUpdate());
    }

    void grantModerator(UUID userPublicId) {
        txTemplate.executeWithoutResult(s -> {
            Number roleId = (Number) em.createNativeQuery("SELECT id FROM role WHERE name = 'MODERATOR'")
                    .getSingleResult();
            Number userId = (Number) em.createNativeQuery("SELECT id FROM app_user WHERE public_id = :pid")
                    .setParameter("pid", userPublicId).getSingleResult();
            em.createNativeQuery("""
                    INSERT INTO role_assignment (public_id, version, created_at, deleted, user_id, role_id, status)
                    VALUES (:pid, 0, now(), false, :uid, :rid, 'ACTIVE')
                    """)
                    .setParameter("pid", UUID.randomUUID())
                    .setParameter("uid", userId.longValue())
                    .setParameter("rid", roleId.longValue())
                    .executeUpdate();
        });
    }

    private void seedRole(String name) {
        em.createNativeQuery("""
                INSERT INTO role (public_id, version, created_at, deleted, name, description)
                VALUES (:pid, 0, now(), false, :name, :name)
                """).setParameter("pid", UUID.randomUUID()).setParameter("name", name).executeUpdate();
    }
}
