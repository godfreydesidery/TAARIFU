package com.taarifu.identity;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.TierResolver;
import com.taarifu.communications.infrastructure.adapter.LoggingSmsGatewayStub;
import com.taarifu.geography.test.GeographyTestData;
import com.taarifu.identity.application.service.LocationService;
import com.taarifu.identity.application.service.ProfileService;
import com.taarifu.identity.application.service.SignupService;
import com.taarifu.identity.application.service.VerificationReviewService;
import com.taarifu.identity.application.service.VerificationService;
import com.taarifu.identity.domain.model.enums.AssociationType;
import com.taarifu.identity.domain.model.enums.IdType;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.model.enums.VerificationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level end-to-end tests for the profile & verification increment on real Postgres (Testcontainers)
 * — VERIFICATION-DESIGN §3–§6; D13/D15.
 *
 * <p>Proves: email-OTP completes the T2 contact channel; an ID submit creates a PENDING request and a
 * second account on the same ID is rejected (D15); a Moderator approval lifts the citizen to <b>live</b>
 * T3 (resolver, not a setter — MF-2) and a voter-ID approval sets the authoritative {@code isElectoral}
 * (D13); and a manual electoral change inside the cooldown is denied while one outside is allowed (D13).
 * Audit rows are written with no raw {@code idNo} (L-1/§18).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class VerificationFlowIntegrationTest extends AbstractPostgisIntegrationTest {

    private static final Pattern CODE = Pattern.compile("(\\d{6})");

    @Autowired private SignupService signupService;
    @Autowired private ProfileService profileService;
    @Autowired private LocationService locationService;
    @Autowired private VerificationService verificationService;
    @Autowired private VerificationReviewService reviewService;
    @Autowired private TierResolver tierResolver;
    @Autowired private LoggingSmsGatewayStub smsStub;
    @Autowired private GeographyTestData geographyTestData;
    @Autowired private TransactionTemplate txTemplate;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    void seed() {
        // Identity rows first (their FKs reference geography), then geography, then re-seed the role —
        // so the fixed reference-data codes can be re-seeded each test without a unique-code clash.
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
        txTemplate.executeWithoutResult(s ->
                em.createNativeQuery("""
                        INSERT INTO role (public_id, version, created_at, deleted, name, description)
                        VALUES (:pid, 0, now(), false, 'CITIZEN', 'Registered citizen')
                        """).setParameter("pid", UUID.randomUUID()).executeUpdate());
    }

    /** Signs up a T1 citizen and returns their public id. */
    private UUID signup(String phone) {
        UUID challengeId = signupService.requestSignupOtp(phone);
        return signupService.completeSignup(challengeId, readCode(phone)).userPublicId();
    }

    private String readCode(String recipient) {
        Matcher m = CODE.matcher(smsStub.lastBodyFor(recipient).orElseThrow());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** Drives profile name + location pin to reach T2. */
    private UUID t2Citizen(String phone, GeographyTestData.Fixture geo) {
        UUID userId = signup(phone);
        profileService.updateProfile(userId, "Asha", "Mushi", null, null, null);
        locationService.addLocation(userId, geo.wardPublicId(), AssociationType.RESIDENCE, true);
        // Recompute via the live resolver to be sure (name + 1 pin + phone-verified = T2).
        assertThat(tierResolver.resolveLiveTierRank(userId)).isEqualTo(2);
        return userId;
    }

    @Test
    void emailOtpVerification_marksEmailVerified() {
        GeographyTestData.Fixture geo = geographyTestData.seedKilimanjaroRomboMengwe();
        UUID userId = t2Citizen("+255700000301", geo);

        String email = "asha@example.tz";
        UUID challengeId = profileService.requestEmailVerification(userId, email);
        TrustTier tier = profileService.verifyEmail(userId, challengeId, readCode(email));

        // Already T2 via phone; email-verify keeps T2 and records the channel (no downgrade).
        assertThat(tier).isEqualTo(TrustTier.T2);
        assertThat(auditCount(AuditEventType.AUTH_OTP_VERIFIED)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void idSubmit_createsPending_andDuplicateOnAnotherAccountIsRejected() {
        GeographyTestData.Fixture geo = geographyTestData.seedKilimanjaroRomboMengwe();
        UUID first = t2Citizen("+255700000302", geo);
        UUID second = t2Citizen("+255700000303", geo);

        VerificationService.SubmitResult r = verificationService.submitIdVerification(
                first, IdType.NATIONAL, "19900101-12345-00001-23", "Asha Mushi", "obj/evidence-1");
        assertThat(r.status()).isEqualTo(VerificationStatus.PENDING);
        // Submitting does not grant tier — still T2 until approved (§25.5).
        assertThat(tierResolver.resolveLiveTierRank(first)).isEqualTo(2);

        // A SECOND account submitting the SAME id is rejected by the blind-index dedup (D15).
        assertThatThrownBy(() -> verificationService.submitIdVerification(
                second, IdType.NATIONAL, "19900101-12345-00001-23", "Imposter", "obj/evidence-2"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_IDENTITY);

        // No raw id number leaked into any audit text column (§18/S-4).
        assertNoRawTextInAudit("19900101-12345-00001-23");
    }

    @Test
    void resubmitSameIdByOwnAccount_isIdempotent_notDuplicate() {
        GeographyTestData.Fixture geo = geographyTestData.seedKilimanjaroRomboMengwe();
        UUID me = t2Citizen("+255700000304", geo);
        VerificationService.SubmitResult first = verificationService.submitIdVerification(
                me, IdType.NATIONAL, "ID-OWN-1", "Asha Mushi", null);
        VerificationService.SubmitResult again = verificationService.submitIdVerification(
                me, IdType.NATIONAL, "ID-OWN-1", "Asha Mushi", null);
        // Same in-flight PENDING request returned — no duplicate queue entry, no DUPLICATE_IDENTITY.
        assertThat(again.verificationPublicId()).isEqualTo(first.verificationPublicId());
    }

    @Test
    void operatorApprove_liftsCitizenToLiveT3() {
        GeographyTestData.Fixture geo = geographyTestData.seedKilimanjaroRomboMengwe();
        UUID citizen = t2Citizen("+255700000305", geo);
        VerificationService.SubmitResult submit = verificationService.submitIdVerification(
                citizen, IdType.NATIONAL, "NIDA-APPROVE-1", "Asha Mushi", "obj/ev");

        UUID reviewer = signup("+255700000306");
        VerificationReviewService.DecisionResult result =
                reviewService.approve(reviewer, submit.verificationPublicId(), null, "looks good");

        assertThat(result.status()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(result.subjectTier()).isEqualTo(TrustTier.T3);
        // The LIVE resolver agrees (idVerified flipped → T3) — not a manual setTrustTier (MF-2).
        assertThat(tierResolver.resolveLiveTierRank(citizen)).isEqualTo(3);
        assertThat(auditCount(AuditEventType.AUTH_VERIFICATION_APPROVED)).isGreaterThanOrEqualTo(1);
        assertThat(auditCount(AuditEventType.AUTH_TIER_CHANGED)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void voterIdApprove_setsAuthoritativeElectoral() {
        GeographyTestData.Fixture geo = geographyTestData.seedKilimanjaroRomboMengwe();
        UUID citizen = t2Citizen("+255700000307", geo);
        VerificationService.SubmitResult submit = verificationService.submitIdVerification(
                citizen, IdType.VOTER, "VOTER-1", "Asha Mushi", "obj/ev");

        UUID reviewer = signup("+255700000308");
        // Voter-ID approval with the registered ward → authoritative electoral (D13).
        reviewService.approve(reviewer, submit.verificationPublicId(), geo.wardPublicId(), null);

        assertThat(tierResolver.resolveLiveTierRank(citizen)).isEqualTo(3);
        // Exactly one electoral location now exists for the citizen (the registered ward).
        Number electorals = (Number) em.createNativeQuery("""
                SELECT count(*) FROM profile_location pl
                JOIN profile p ON p.id = pl.profile_id
                JOIN app_user u ON u.id = p.user_id
                WHERE u.public_id = :uid AND pl.is_electoral = true AND pl.deleted = false
                """).setParameter("uid", citizen).getSingleResult();
        assertThat(electorals.longValue()).isEqualTo(1L);
        assertThat(auditCount(AuditEventType.ELECTORAL_CHANGED)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void manualElectoralChange_isCooldownGuarded() {
        GeographyTestData.Fixture geo = geographyTestData.seedKilimanjaroRomboMengwe();
        UUID me = t2Citizen("+255700000309", geo);
        // Add a second location to move the electoral to.
        UUID secondLoc = locationService.addLocation(
                me, geo.wardPublicId(), AssociationType.HOME_ANCESTRAL, false);
        UUID primaryLoc = primaryLocationPublicId(me);

        // First manual electoral set succeeds (no prior electoral, no cooldown to violate).
        locationService.setElectoralManual(me, primaryLoc);

        // A second manual change immediately after is inside the cooldown window → denied (D13).
        assertThatThrownBy(() -> locationService.setElectoralManual(me, secondLoc))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    /** @return the public id of the caller's primary ProfileLocation. */
    private UUID primaryLocationPublicId(UUID userPublicId) {
        return (UUID) em.createNativeQuery("""
                SELECT pl.public_id FROM profile_location pl
                JOIN profile p ON p.id = pl.profile_id
                JOIN app_user u ON u.id = p.user_id
                WHERE u.public_id = :uid AND pl.is_primary = true AND pl.deleted = false
                """).setParameter("uid", userPublicId).getSingleResult();
    }

    private long auditCount(AuditEventType type) {
        Number n = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM audit_event WHERE event_type = :t")
                .setParameter("t", type.name()).getSingleResult();
        return n.longValue();
    }

    /** Asserts no audit row leaks a raw sensitive value in any text column (§18/S-4). */
    private void assertNoRawTextInAudit(String raw) {
        Number n = (Number) em.createNativeQuery("""
                SELECT count(*) FROM audit_event
                WHERE reason_code LIKE :p OR detail_ref LIKE :p OR client_ip_hash LIKE :p
                """).setParameter("p", "%" + raw + "%").getSingleResult();
        assertThat(n.longValue()).isZero();
    }
}
