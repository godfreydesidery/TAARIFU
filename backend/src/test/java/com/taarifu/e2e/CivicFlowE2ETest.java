package com.taarifu.e2e;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.FlywayCleanMigrateTestConfig;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.outbox.OutboxRelay;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.communications.infrastructure.adapter.LoggingSmsGatewayStub;
import com.taarifu.engagement.application.service.PetitionService;
import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.enums.PetitionTargetType;
import com.taarifu.engagement.domain.repository.PetitionRepository;
import com.taarifu.geography.test.GeographyTestData;
import com.taarifu.identity.api.ElectoralScopeApi;
import com.taarifu.identity.application.service.SignupService;
import com.taarifu.institutions.api.RepresentativeQueryApi;
import com.taarifu.reporting.api.dto.IssueCategoryDto;
import com.taarifu.reporting.api.dto.CreateIssueCategoryDto;
import com.taarifu.reporting.api.dto.FileReportDto;
import com.taarifu.reporting.api.dto.ReportDto;
import com.taarifu.reporting.application.service.IssueCategoryService;
import com.taarifu.reporting.application.service.ReportService;
import com.taarifu.reporting.domain.model.enums.ReportStatus;
import com.taarifu.responders.api.dto.CreateOrganisationRequest;
import com.taarifu.responders.api.dto.CreateResponderRequest;
import com.taarifu.responders.api.dto.CreateRoutingRuleRequest;
import com.taarifu.responders.api.dto.OrganisationDto;
import com.taarifu.responders.api.dto.ResponderDto;
import com.taarifu.responders.api.dto.UpdateOrganisationRequest;
import com.taarifu.responders.api.dto.UpdateResponderRequest;
import com.taarifu.responders.application.service.ResponderAdminService;
import com.taarifu.responders.domain.model.enums.AssignmentRole;
import com.taarifu.responders.domain.model.enums.CoverageType;
import com.taarifu.responders.domain.model.enums.OrganisationStatus;
import com.taarifu.responders.domain.model.enums.OrganisationType;
import com.taarifu.responders.domain.model.enums.ProviderSelectionMode;
import com.taarifu.responders.domain.model.enums.ResponderStatus;
import com.taarifu.responders.domain.model.enums.ResponderType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end <b>core civic-flow</b> contract + smoke tests on the merged platform, against a real PostGIS
 * Testcontainer (ADR-0009; PRD §10/§12/§24; ADR-0013/0014). This is the cross-module join the per-module
 * tests cannot cover — it proves the flows that span identity → reporting → outbox → responders → engagement
 * actually compose.
 *
 * <p>Covered (each maps to a launch-readiness criterion):</p>
 * <ol>
 *   <li><b>Citizen happy path</b> (PRD §10, ADR-0014 §5b; LAUNCH-READINESS D3/A4): signup OTP → T1 → file a
 *       report → the report auto-routes to a single OWNER {@code ResponderAssignment} <b>via the outbox
 *       relay</b> (REPORT_ROUTED → {@code RoutingHandler}, asynchronous, ids-only) → the citizen tracks it.</li>
 *   <li><b>Responder lifecycle scope — R-1</b> (PRD §24.4, D13): an in-scope agent drives a transition; an
 *       out-of-scope agent is denied {@code OUT_OF_SCOPE} (403). Scope is supplied by a test {@link ScopeGuard}
 *       so the lifecycle-gate contract is exercised deterministically (the live DB-backed resolver has its own
 *       unit/IT coverage).</li>
 *   <li><b>Electoral fence</b> (PRD §23.5, D13/F1): a non-elector signing a representative-targeted petition
 *       is denied {@code OUT_OF_SCOPE} — tokens/quota never enter the path. The signer's electoral location and
 *       the rep's seat are supplied by test ports so the engagement fence is exercised in isolation.</li>
 * </ol>
 *
 * <p><b>Why drive the relay explicitly.</b> The outbox relay polls on a 1s schedule, but a test must be
 * deterministic — so {@link #drainOutbox()} calls {@link OutboxRelay#poll()} directly until the asynchronous
 * routing effect has landed, rather than sleeping. This exercises the exact production relay code
 * (claim → dispatch → mark), just on the test's thread.</p>
 *
 * <p>TEST-ONLY: this class and its {@code @TestConfiguration} add no production code. It runs on the
 * {@code test} profile (Docker required; CI-gated).</p>
 *
 * <p><b>WHY Flyway-owned schema + {@code ddl-auto=validate}</b> (the test-harness fix, wave4-review §4):
 * the citizen happy path files a report, which mints its {@code TAR-} ticket from the Postgres sequence
 * {@code report_code_seq} created by Flyway (V23) and never by Hibernate's entity-derived create-drop —
 * so under the shared profile's create-drop this E2E failed with {@code relation "report_code_seq" does
 * not exist}. Booting the real migrated schema (the production configuration) is the faithful fix. The
 * {@link #seedAndReset()} fixture deletes the Flyway-seeded reference rows it re-creates (issue category,
 * role catalogue) and wipes the seeded geography before re-seeding its own, so the fixture is independent
 * of the national seed and each method starts from a known slate. No production behaviour is changed.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FlywayCleanMigrateTestConfig.class)   // clean-then-migrate so create-drop leftovers in the shared container never block Flyway
@TestPropertySource(properties = {
        // Override the shared test profile's create-drop with the production schema path: Flyway owns the
        // schema (so report_code_seq exists, V23) and Hibernate only validates the entities against it.
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        // Allow Flyway.clean() in tests ONLY (production keeps clean disabled) so the Flyway tests start from
        // an empty schema regardless of create-drop residue in the shared container (FlywayCleanMigrateTestConfig).
        "spring.flyway.clean-disabled=false",
        // Park the @Scheduled background outbox relay far out so it never fires during a test method. This
        // class drives the relay DETERMINISTICALLY via drainOutbox()/drainOutboxUntil() (see class Javadoc);
        // a concurrent 1s background poll would race the explicit drain — claiming a method's REPORT_ROUTED
        // row on another thread (so the explicit drain sees nothing land for filed.id()) and producing the
        // intermittent "no OWNER assignment" failure. The relay logic is still fully exercised by the
        // explicit poll() calls — only the background timer is silenced (TEST-ONLY).
        "taarifu.outbox.poll-interval-ms=3600000"
})
class CivicFlowE2ETest extends AbstractPostgisIntegrationTest {

    private static final Pattern OTP_CODE = Pattern.compile("(\\d{6})");

    @Autowired private SignupService signupService;
    @Autowired private LoggingSmsGatewayStub smsStub;
    @Autowired private ReportService reportService;
    @Autowired private IssueCategoryService categoryService;
    @Autowired private ResponderAdminService responderAdminService;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private PetitionService petitionService;
    @Autowired private PetitionRepository petitionRepository;
    @Autowired private GeographyTestData geographyTestData;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private TestScopeGuard testScopeGuard;
    @Autowired private TestRepresentativeQueryApi testRepresentativeQueryApi;
    @Autowired private TestElectoralScopeApi testElectoralScopeApi;

    @PersistenceContext private EntityManager em;

    private UUID wardId;

    // -------------------------------------------------------------------------------------------------
    // Test doubles — deterministic, context-scoped seams (NO production change). Each is @Primary so it
    // wins over the live bean ONLY in this test context.
    // -------------------------------------------------------------------------------------------------

    @TestConfiguration
    static class CivicFlowTestConfig {

        /** Test {@link ScopeGuard}: flip in/out-of-scope to drive the R-1 responder-lifecycle gate. */
        @Bean @Primary
        TestScopeGuard testScopeGuard() {
            return new TestScopeGuard();
        }

        /** Test rep-seat resolver: returns a fixed constituency so the engagement electoral fence runs. */
        @Bean @Primary
        TestRepresentativeQueryApi testRepresentativeQueryApi() {
            return new TestRepresentativeQueryApi();
        }

        /** Test elector check: deny → non-elector → OUT_OF_SCOPE on a rep-targeted petition. */
        @Bean @Primary
        TestElectoralScopeApi testElectoralScopeApi() {
            return new TestElectoralScopeApi();
        }
    }

    /** Settable scope guard — {@code inScope} drives canActOnArea/canActOnCategory; isNotSelf always true. */
    static final class TestScopeGuard implements ScopeGuard {
        volatile boolean inScope = true;
        @Override public boolean canActOnArea(UUID areaPublicId) { return inScope; }
        @Override public boolean canActOnCategory(UUID categoryPublicId) { return inScope; }
        @Override public boolean canActInConstituency(UUID constituencyPublicId) { return inScope; }
        @Override public boolean isNotSelf(UUID subjectPublicId) { return true; }
    }

    /** Test rep-seat port: every target rep is a constituency-MP for the fixed test constituency. */
    static final class TestRepresentativeQueryApi implements RepresentativeQueryApi {
        volatile UUID constituencyId = UUID.randomUUID();
        @Override public boolean exists(UUID representativePublicId) {
            // The e2e flow targets a real, existing rep — every non-null id resolves as present.
            return representativePublicId != null;
        }
        @Override public Optional<UUID> constituencyOf(UUID representativePublicId) {
            return Optional.of(constituencyId);
        }
        @Override public Optional<UUID> wardOf(UUID representativePublicId) {
            return Optional.empty();
        }
        @Override public boolean ownsRepresentative(UUID accountPublicId, UUID representativePublicId) {
            // This e2e flow exercises the rate-rep binding action, not the rep self-reply ownership path —
            // no account is the rep's linked account here, so the ownership guard is closed (deny-by-default).
            return false;
        }
    }

    /** Test elector port: {@code elector} controls the verdict; defaults to NON-elector (deny). */
    static final class TestElectoralScopeApi implements ElectoralScopeApi {
        volatile boolean elector = false;
        @Override public boolean isElectorOf(UUID userPublicId, UUID constituencyPublicId) { return elector; }
        @Override public boolean isElectorOfWard(UUID userPublicId, UUID wardPublicId) { return elector; }
    }

    // -------------------------------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------------------------------

    @BeforeEach
    void seedAndReset() {
        testScopeGuard.inScope = true;
        testElectoralScopeApi.elector = false;
        // Clean the cross-module tables this suite touches (create-drop leaves rows across methods), in FK order.
        txTemplate.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM outbox_event").executeUpdate();
            em.createNativeQuery("DELETE FROM responder_assignment").executeUpdate();
            em.createNativeQuery("DELETE FROM routing_rule").executeUpdate();
            em.createNativeQuery("DELETE FROM responder_coverage_area").executeUpdate();
            em.createNativeQuery("DELETE FROM responder_category").executeUpdate();
            em.createNativeQuery("DELETE FROM responder").executeUpdate();
            em.createNativeQuery("DELETE FROM responder_organisation").executeUpdate();
            em.createNativeQuery("DELETE FROM petition_signature").executeUpdate();
            em.createNativeQuery("DELETE FROM petition").executeUpdate();
            em.createNativeQuery("DELETE FROM case_event").executeUpdate();
            em.createNativeQuery("DELETE FROM report").executeUpdate();
            em.createNativeQuery("DELETE FROM issue_category").executeUpdate();
            em.createNativeQuery("DELETE FROM otp_challenge").executeUpdate();
            em.createNativeQuery("DELETE FROM refresh_token").executeUpdate();
            em.createNativeQuery("DELETE FROM role_assignment").executeUpdate();
            em.createNativeQuery("DELETE FROM profile_location").executeUpdate();
            em.createNativeQuery("DELETE FROM profile").executeUpdate();
            em.createNativeQuery("DELETE FROM app_user").executeUpdate();
            em.createNativeQuery("DELETE FROM role").executeUpdate();
            // CITIZEN catalogue row the signup flow grants.
            em.createNativeQuery("""
                    INSERT INTO role (public_id, version, created_at, deleted, name, description)
                    VALUES (:pid, 0, now(), false, 'CITIZEN', 'Registered citizen')
                    """).setParameter("pid", UUID.randomUUID()).executeUpdate();
        });
        geographyTestData.clear();
        wardId = geographyTestData.seedKilimanjaroRomboMengwe().wardPublicId();
    }

    // -------------------------------------------------------------------------------------------------
    // (1) Citizen happy path: signup → T1 → file → auto-route to OWNER via the outbox → track
    // -------------------------------------------------------------------------------------------------

    /**
     * The end-to-end MVP loop spine (LAUNCH-READINESS A4/D3): a citizen self-registers to T1, files a report,
     * the report auto-routes to a single OWNER {@code ResponderAssignment} through the transactional outbox
     * relay (REPORT_ROUTED → {@code RoutingHandler}, asynchronous), and the citizen can track the report.
     */
    @Test
    void citizenHappyPath_signupFileAutoRouteTrack() {
        // --- responders side: an ACTIVE+VERIFIED org, an ACTIVE responder covering the ward + handling the
        //     category, and an active routing rule — the configuration auto-routing needs (§24.2, §25.2). ---
        IssueCategoryDto category = createCategory("WATER_SANITATION", "Maji na Usafi");

        OrganisationDto org = responderAdminService.createOrganisation(new CreateOrganisationRequest(
                "DAWASA", OrganisationType.UTILITY, null, null, null));
        responderAdminService.updateOrganisation(org.id(), new UpdateOrganisationRequest(
                "DAWASA", OrganisationType.UTILITY, OrganisationStatus.ACTIVE, null, null, null));
        responderAdminService.setOrganisationVerified(org.id(), true);

        ResponderDto responder = responderAdminService.createResponder(org.id(), new CreateResponderRequest(
                "DAWASA — Kilimanjaro", ResponderType.UTILITY, CoverageType.AREAS,
                List.of(category.id()), List.of(wardId), null));
        // Activate the capability (created PENDING) so it participates in routing (§24.1).
        responderAdminService.updateResponder(responder.id(), new UpdateResponderRequest(
                "DAWASA — Kilimanjaro", ResponderType.UTILITY, ResponderStatus.ACTIVE, CoverageType.AREAS,
                List.of(category.id()), List.of(wardId), null));

        responderAdminService.createRoutingRule(new CreateRoutingRuleRequest(
                category.id(), null, ResponderType.UTILITY, ProviderSelectionMode.AUTO_BY_AREA, null, null));

        // --- citizen side: signup OTP → T1 → file the report. ---
        String phone = "+255700000201";
        UUID challenge = signupService.requestSignupOtp(phone);
        SignupService.SignupResult signup = signupService.completeSignup(challenge, readOtp(phone));
        UUID reporter = signup.userPublicId();

        ReportDto filed = reportService.fileReport(reporter, new FileReportDto(
                category.id(), "Bomba limepasuka", "Maji yanamwagika barabarani",
                wardId, -3.05, 37.55, "PUBLIC", false, null));
        assertThat(filed.code()).startsWith("TAR-");
        assertThat(filed.status()).isEqualTo(ReportStatus.NEW.name());

        // --- the asynchronous routing effect: drive the relay until the OWNER assignment lands. ---
        boolean routed = drainOutboxUntil(() -> ownerAssignmentExists(filed.id()));
        assertThat(routed)
                .as("report should auto-route to a single OWNER ResponderAssignment via the outbox relay (D21)")
                .isTrue();
        assertThat(ownerAssignmentCount(filed.id()))
                .as("exactly one OWNER per report (§24.3 single-OWNER invariant)")
                .isEqualTo(1L);

        // --- track: the citizen still owns and can read the report end-to-end. ---
        ReportDto tracked = reportService.getMyReport(reporter, filed.id());
        assertThat(tracked.id()).isEqualTo(filed.id());
    }

    /**
     * Routing fallback (PRD §25.2): when NO eligible responder/rule exists, filing still succeeds and the
     * REPORT_ROUTED event is a no-op success (no OWNER, no DLQ) — the report stays unrouted for manual
     * assignment. Proves a config gap never blocks the citizen path or poisons the outbox.
     */
    @Test
    void fileReport_withNoRoutingConfig_leavesUnroutedWithoutFailing() {
        IssueCategoryDto category = createCategory("ROADS", "Barabara");

        String phone = "+255700000202";
        UUID challenge = signupService.requestSignupOtp(phone);
        UUID reporter = signupService.completeSignup(challenge, readOtp(phone)).userPublicId();

        ReportDto filed = reportService.fileReport(reporter, new FileReportDto(
                category.id(), "Shimo", "Barabara mbovu", wardId, null, null, "PUBLIC", false, null));

        // Drain the relay; the REPORT_ROUTED handler finds no responder and PROCESSES the row as a no-op.
        drainOutbox();
        assertThat(ownerAssignmentExists(filed.id()))
                .as("no eligible responder → no OWNER, report left unrouted (§25.2)").isFalse();
        // No row stuck FAILED (the config gap is a no-op success, never a DLQ entry).
        assertThat(failedOutboxCount()).isZero();
    }

    // -------------------------------------------------------------------------------------------------
    // (2) Responder lifecycle scope (R-1): in-scope transitions; out-of-scope → OUT_OF_SCOPE (403)
    // -------------------------------------------------------------------------------------------------

    /**
     * R-1 horizontal authorization (PRD §24.4): an out-of-scope agent attempting a case-lifecycle action is
     * denied {@code OUT_OF_SCOPE}; the same action by an in-scope agent is permitted. Drives
     * {@link ResponderAdminService#resolveCase} so the scope gate ({@code requireActorInReportScope}) is on the
     * path. (resolveCase on a NEW report would also be a CONFLICT in reporting — but the scope gate runs FIRST,
     * so an out-of-scope agent never reaches the state machine; that ordering is exactly what R-1 guarantees.)
     */
    @Test
    void responderLifecycle_outOfScopeAgentDenied_inScopeAgentPasses() {
        IssueCategoryDto category = createCategory("HEALTH", "Afya");

        String phone = "+255700000203";
        UUID challenge = signupService.requestSignupOtp(phone);
        UUID reporter = signupService.completeSignup(challenge, readOtp(phone)).userPublicId();
        ReportDto filed = reportService.fileReport(reporter, new FileReportDto(
                category.id(), "Zahanati imefungwa", "Hakuna mtumishi", wardId, null, null,
                "PUBLIC", false, null));

        UUID agent = UUID.randomUUID();

        // Out-of-scope: the R-1 gate denies BEFORE any state transition is attempted.
        testScopeGuard.inScope = false;
        assertThatThrownBy(() -> responderAdminService.resolveCase(filed.id(), agent, "done"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.OUT_OF_SCOPE);

        // In-scope: the R-1 gate passes, so the call proceeds into reporting's state machine. resolving a NEW
        // report is an illegal transition there → CONFLICT (NOT OUT_OF_SCOPE). The error code switching from
        // OUT_OF_SCOPE to CONFLICT is the proof the scope gate is no longer the thing blocking the agent.
        testScopeGuard.inScope = true;
        assertThatThrownBy(() -> responderAdminService.resolveCase(filed.id(), agent, "done"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // -------------------------------------------------------------------------------------------------
    // (3) Electoral fence (D13/F1, §23.5): non-elector signing a rep-targeted petition → OUT_OF_SCOPE
    // -------------------------------------------------------------------------------------------------

    /**
     * The civic-integrity fence (PRD §23.5, D13/F1): a signer who is NOT an elector of the target
     * representative's seat is denied {@code OUT_OF_SCOPE} when signing a REPRESENTATIVE-targeted petition.
     * Tokens/quota are never read on this path — the fence is electoral location, not balance. Flipping the
     * test elector port to {@code true} lets the same signer through, proving the fence is the only thing
     * gating them on the scope axis.
     */
    @Test
    void petitionSign_nonElectorOfRepSeat_isOutOfScope() {
        UUID creator = UUID.randomUUID();
        UUID targetRep = UUID.randomUUID();
        UUID petitionId = seedActivePetition("Tunaomba maji safi", PetitionTargetType.REPRESENTATIVE,
                targetRep, creator);

        UUID signer = UUID.randomUUID();

        // Non-elector (default) → OUT_OF_SCOPE on the electoral-scope axis (F1).
        testElectoralScopeApi.elector = false;
        assertThatThrownBy(() -> petitionService.sign(petitionId, signer, null, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.OUT_OF_SCOPE);

        // Elector of the rep's seat → the fence passes; the binding act completes and the count bumps.
        testElectoralScopeApi.elector = true;
        var signed = petitionService.sign(petitionId, signer, null, false);
        assertThat(signed.signatureCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------------

    /** Reads the 6-digit OTP the dev SMS stub captured for a phone (zero external calls). */
    private String readOtp(String phone) {
        String body = smsStub.lastBodyFor(phone).orElseThrow();
        Matcher m = OTP_CODE.matcher(body);
        assertThat(m.find()).as("OTP code present in SMS body").isTrue();
        return m.group(1);
    }

    /** Creates a routable PUBLIC issue category with sane SLA windows. */
    private IssueCategoryDto createCategory(String code, String nameSw) {
        return categoryService.create(new CreateIssueCategoryDto(
                code, nameSw, null, "SECTOR_UTILITY", 2880, 20160, false, false, "PUBLIC", "icon"));
    }

    /**
     * Inserts a petition directly (the seed path), then marks it ACTIVE, so {@link PetitionService#sign} can
     * exercise the fence without the moderation publish flow. Uses native commit via a programmatic tx so the
     * service (on its own connection) sees the row.
     */
    private UUID seedActivePetition(String title, PetitionTargetType targetType, UUID targetId, UUID creator) {
        return txTemplate.execute(s -> {
            Petition p = Petition.create(title, "tafadhali", targetType, targetId, 100, null, creator, null);
            p.activate();
            return petitionRepository.save(p).getPublicId();
        });
    }

    /** Drives the relay once (one poll cycle). */
    private void drainOutbox() {
        outboxRelay.poll();
    }

    /**
     * Drives the relay repeatedly (bounded) until {@code condition} holds, exercising the production
     * claim→dispatch→mark loop deterministically instead of waiting on the 1s schedule. Each poll runs in its
     * own transaction; back-events the first poll appends (e.g. RESPONDER_ASSIGNED) are picked up by the next.
     *
     * @return {@code true} if the condition became true within the poll budget.
     */
    private boolean drainOutboxUntil(java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < 10; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            outboxRelay.poll();
        }
        return condition.getAsBoolean();
    }

    /** @return whether a single-OWNER assignment exists for the report. */
    private boolean ownerAssignmentExists(UUID reportId) {
        return ownerAssignmentCount(reportId) > 0;
    }

    /** @return the number of OWNER assignments on the report (the single-OWNER invariant expects ≤ 1). */
    private long ownerAssignmentCount(UUID reportId) {
        Number n = (Number) em.createNativeQuery("""
                        SELECT count(*) FROM responder_assignment
                        WHERE report_id = :rid AND role = :role
                        """)
                .setParameter("rid", reportId)
                .setParameter("role", AssignmentRole.OWNER.name())
                .getSingleResult();
        return n.longValue();
    }

    /** @return the number of FAILED (DLQ) outbox rows — must stay zero on the happy/fallback paths. */
    private long failedOutboxCount() {
        Number n = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM outbox_event WHERE status = 'FAILED'")
                .getSingleResult();
        return n.longValue();
    }
}
