package com.taarifu.identity;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.security.JwtService;
import com.taarifu.communications.infrastructure.adapter.LoggingSmsGatewayStub;
import com.taarifu.identity.application.service.SignupService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MF-2 keystone end-to-end: a forged/elevated {@code trustTier} claim is ignored by the live gate.
 *
 * <p>Responsibility: signs up a genuine <b>T1</b> citizen, then mints an access token whose
 * {@code trustTier} claim is forged to <b>T3</b> and calls the T3-gated demo endpoint. The
 * {@code RequiresTierAspect} resolves the caller's <b>live</b> tier (T1) from the DB and returns
 * {@code 403 TIER_TOO_LOW} — proving the token claim never escalates (AUTH-DESIGN §7.2, ADR-0011 §3).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TierGateForgedClaimIntegrationTest extends AbstractPostgisIntegrationTest {

    private static final Pattern CODE = Pattern.compile("(\\d{6})");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private SignupService signupService;
    @Autowired private LoggingSmsGatewayStub smsStub;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    @Transactional
    void seed() {
        em.createNativeQuery("DELETE FROM audit_event").executeUpdate();
        em.createNativeQuery("DELETE FROM refresh_token").executeUpdate();
        em.createNativeQuery("DELETE FROM otp_challenge").executeUpdate();
        em.createNativeQuery("DELETE FROM role_assignment").executeUpdate();
        em.createNativeQuery("DELETE FROM profile").executeUpdate();
        em.createNativeQuery("DELETE FROM app_user").executeUpdate();
        em.createNativeQuery("DELETE FROM role").executeUpdate();
        em.createNativeQuery("""
                INSERT INTO role (public_id, version, created_at, deleted, name, description)
                VALUES (:pid, 0, now(), false, 'CITIZEN', 'Registered citizen')
                """).setParameter("pid", UUID.randomUUID()).executeUpdate();
    }

    @Test
    void forgedT3Claim_isBlocked_becauseLiveTierIsT1() throws Exception {
        String phone = "+255700000201";
        UUID challengeId = signupService.requestSignupOtp(phone);
        Matcher m = CODE.matcher(smsStub.lastBodyFor(phone).orElseThrow());
        assertThat(m.find()).isTrue();
        SignupService.SignupResult signup = signupService.completeSignup(challengeId, m.group(1));

        // Forge a VALID-SIGNATURE access token but with a trustTier=T3 claim for this T1 user.
        String forged = jwtService.issueAccessToken(signup.userPublicId(), List.of("CITIZEN"), "T3");

        mockMvc.perform(get("/api/v1/demo/t3-action")
                        .header("Authorization", "Bearer " + forged))
                .andExpect(status().isForbidden())
                // Top-level statusCode is the integer HTTP status; the stable machine code that
                // distinguishes TIER_TOO_LOW from other 403s now lives at data.code (ADR-0008).
                .andExpect(jsonPath("$.statusCode").value(403))
                .andExpect(jsonPath("$.data.code").value("TIER_TOO_LOW"));
    }

    @Test
    void genuineT1_isBlockedFromT3_action() throws Exception {
        String phone = "+255700000202";
        UUID challengeId = signupService.requestSignupOtp(phone);
        Matcher m = CODE.matcher(smsStub.lastBodyFor(phone).orElseThrow());
        assertThat(m.find()).isTrue();
        SignupService.SignupResult signup = signupService.completeSignup(challengeId, m.group(1));

        // The honestly-issued T1 token is likewise blocked from a T3 action.
        String token = jwtService.issueAccessToken(signup.userPublicId(), List.of("CITIZEN"), "T1");
        mockMvc.perform(get("/api/v1/demo/t3-action")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
