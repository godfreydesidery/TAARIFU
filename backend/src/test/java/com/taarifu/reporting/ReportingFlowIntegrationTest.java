package com.taarifu.reporting;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.FlywayCleanMigrateTestConfig;
import com.taarifu.geography.test.GeographyTestData;
import com.taarifu.reporting.api.dto.CaseEventDto;
import com.taarifu.reporting.api.dto.CreateIssueCategoryDto;
import com.taarifu.reporting.api.dto.FileReportDto;
import com.taarifu.reporting.api.dto.IssueCategoryDto;
import com.taarifu.reporting.api.dto.PublicReportDto;
import com.taarifu.reporting.api.dto.ReportDto;
import com.taarifu.reporting.application.service.IssueCategoryService;
import com.taarifu.reporting.application.service.ReportService;
import com.taarifu.reporting.domain.model.enums.ReportStatus;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers integration test for the reporting slice (ADR-0009; PRD §10 M3, §12.1, §25.3).
 *
 * <p>Responsibility: exercises the full case-management slice against a real PostGIS database — entities
 * → repositories → services — proving the schema persists, the SLA/ticket-code/state-machine logic works
 * on real data, and the privacy filters hold (a PRIVATE report never appears in the public list; an
 * anonymous sensitive filing stores no reporter; ownership reads are scoped). Docker is required, so this
 * runs in CI; the unit tests cover the same invariants without a database for the local gate.</p>
 *
 * <p>WHY service-level (not full HTTP with JWT here): the file endpoint's T2 tier gate is the kernel's
 * concern (covered by the auth increment's tests); this test focuses on the reporting domain rules and
 * the migration/repository round-trip, which is where this module's risk lives.</p>
 *
 * <p><b>WHY Flyway-owned schema + {@code ddl-auto=validate} here</b> (the test-harness fix, wave4-review
 * §4 central need; ADR-0005/0009): filing a report mints its {@code TAR-YYYY-NNNNNN} ticket code from the
 * Postgres sequence {@code report_code_seq}, which is created by Flyway (V23) — <b>not</b> by Hibernate
 * (the application draws it with a raw {@code SELECT nextval(...)}, so Hibernate's entity-derived
 * {@code create-drop} schema never contains it). Under the shared {@code test} profile's create-drop this
 * test failed with {@code relation "report_code_seq" does not exist} — even in isolation. Booting the
 * <b>real migrated schema</b> (the exact production configuration) is therefore the only faithful way to
 * exercise the file path, and it incidentally pins that V23 owns the sequence. The fixture seeds its own
 * geography after wiping it ({@link #seed()}), so it does not depend on the Flyway reference-data seed.</p>
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
        // Allow Flyway.clean() in tests ONLY (production keeps clean disabled): the shared static container
        // is reused across create-drop and Flyway tests, so the Flyway tests must clean first to start from
        // an empty schema (see FlywayCleanMigrateTestConfig).
        "spring.flyway.clean-disabled=false"
})
class ReportingFlowIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired
    private ReportService reportService;

    @Autowired
    private IssueCategoryService categoryService;

    @Autowired
    private GeographyTestData geographyTestData;

    @Autowired
    private TransactionTemplate txTemplate;

    @PersistenceContext
    private EntityManager em;

    private final Pageable page = PageRequest.of(0, 20);
    private UUID wardId;

    /**
     * Resets the reporting + reference state this test owns to a known-empty slate, then seeds the
     * Kilimanjaro→Rombo→Mengwe geography fixture.
     *
     * <p>WHY explicit cleanup under the Flyway+validate schema: this class boots the <b>real migrated
     * schema</b> (so {@code report_code_seq} exists — see the class Javadoc). That schema is seeded by
     * Flyway (the national geography of V71–V109 and the issue taxonomy of V82/V83) and — unlike the
     * create-drop slice tests — persists across the methods of this single cached context. So the fixture
     * must (a) delete the Flyway-seeded {@code issue_category} rows and any prior-method {@code report}/
     * {@code case_event} rows so each per-test {@code categoryService.create(...)} runs on a clean,
     * collision-free slate (the seeded codes {@code WATER_SANITATION}/{@code HEALTH}/{@code EDUCATION}
     * would otherwise violate {@code uq_issue_category_code}); and (b) wipe the seeded geography before
     * re-seeding its own (the helper inserts the fixed {@code TZ-19}/{@code TZ-1907} codes, which clash with
     * the seed's national set). Deletions run in FK order inside a committed transaction so the HTTP-less
     * service calls below see a clean database. No production behaviour is touched — test data only.</p>
     */
    @BeforeEach
    void seed() {
        txTemplate.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM case_event").executeUpdate();
            em.createNativeQuery("DELETE FROM report").executeUpdate();
            em.createNativeQuery("DELETE FROM issue_category").executeUpdate();
        });
        geographyTestData.clear();
        wardId = geographyTestData.seedKilimanjaroRomboMengwe().wardPublicId();
    }

    @Test
    void fileTrackConfirm_happyPath() {
        UUID reporter = UUID.randomUUID();
        IssueCategoryDto category = categoryService.create(new CreateIssueCategoryDto(
                "WATER_SANITATION", "Maji na Usafi", null, "SECTOR_UTILITY",
                2880, 20160, false, false, "PUBLIC", "water-drop"));

        ReportDto filed = reportService.fileReport(reporter, new FileReportDto(
                category.id(), "Bomba limepasuka", "Maji yanamwagika barabarani",
                wardId, -3.05, 37.55, "PUBLIC", false, null));

        // Ticket code minted; NEW; SLA set; constituency resolved via geography.
        assertThat(filed.code()).startsWith("TAR-");
        assertThat(filed.status()).isEqualTo(ReportStatus.NEW.name());
        assertThat(filed.dueAt()).isNotNull();
        assertThat(filed.constituencyId()).isNotNull();
        assertThat(filed.latitude()).isEqualTo(-3.05);

        // The reporter can track it and see the initial timeline event.
        ReportDto tracked = reportService.getMyReport(reporter, filed.id());
        assertThat(tracked.id()).isEqualTo(filed.id());
        assertThat(reportService.getMyReportTimeline(reporter, filed.id(), page).getTotalElements())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void publicList_excludesPrivateReport() {
        UUID reporter = UUID.randomUUID();
        IssueCategoryDto publicCat = categoryService.create(new CreateIssueCategoryDto(
                "ROADS", "Barabara", null, "COUNCIL", 4320, 43200, false, false, "PUBLIC", "road"));
        IssueCategoryDto privateCat = categoryService.create(new CreateIssueCategoryDto(
                "LAND_DISPUTE", "Mgogoro wa Ardhi", null, "COUNCIL", 7200, 86400,
                false, true, "PRIVATE", "land"));

        ReportDto publicReport = reportService.fileReport(reporter, new FileReportDto(
                publicCat.id(), "Shimo", "Barabara mbovu", wardId, null, null, "PUBLIC", false, null));
        ReportDto privateReport = reportService.fileReport(reporter, new FileReportDto(
                privateCat.id(), "Mgogoro", "Ardhi yangu", wardId, null, null, "PUBLIC", false, null));

        // Forced PRIVATE overrode the citizen's PUBLIC choice.
        assertThat(privateReport.visibility()).isEqualTo(ReportVisibility.PRIVATE.name());

        // The public list returns the public report but NEVER the private one (§25.3).
        var publicList = reportService.listPublicReports(wardId, page).getContent();
        assertThat(publicList).extracting(PublicReportDto::id).contains(publicReport.id());
        assertThat(publicList).extracting(PublicReportDto::id).doesNotContain(privateReport.id());

        // A direct public fetch of the private report is not-found (existence not revealed).
        assertThatThrownBy(() -> reportService.getPublicReport(privateReport.id()))
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    void anonymousSensitiveFiling_storesNoReporter() {
        UUID reporter = UUID.randomUUID();
        IssueCategoryDto corruption = categoryService.create(new CreateIssueCategoryDto(
                "CORRUPTION", "Rushwa", null, "OVERSIGHT", 1440, 1440, true, true, "PRIVATE", "shield"));

        ReportDto filed = reportService.fileReport(reporter, new FileReportDto(
                corruption.id(), "Rushwa ofisini", "Ofisa amedai rushwa", wardId, null, null,
                "PUBLIC", true, null));

        // Anonymous: no reporter linkage; forced PRIVATE; tracked by code (the pseudonymous handle).
        assertThat(filed.anonymous()).isTrue();
        assertThat(filed.visibility()).isEqualTo(ReportVisibility.PRIVATE.name());

        // Because no reporter is stored, the original account cannot "own"-track it (account-scoped read
        // returns not-found) — the strongest protection (D-Q1, §25.3).
        assertThatThrownBy(() -> reportService.getMyReport(reporter, filed.id()))
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    void confirmResolution_onFreshReport_isConflict() {
        UUID reporter = UUID.randomUUID();
        IssueCategoryDto category = categoryService.create(new CreateIssueCategoryDto(
                "HEALTH", "Afya", null, "COUNCIL", 2880, 30240, false, false, "PRIVATE", "health"));
        ReportDto filed = reportService.fileReport(reporter, new FileReportDto(
                category.id(), "Zahanati imefungwa", "Hakuna mtumishi", wardId, null, null,
                "PRIVATE", false, null));

        // A citizen may confirm/dispute ONLY from RESOLVED (US-3.5). Driving a case into RESOLVED is a
        // responder action (that increment is deferred), so on a fresh NEW report the confirm path is a
        // typed CONFLICT — proving the state-machine guard holds end-to-end against a real database.
        assertThatThrownBy(() -> reportService.confirmResolution(reporter, filed.id(), true, null))
                .hasMessageContaining("CONFLICT");
    }

    @Test
    void addComment_appendsPublicTimelineEvent() {
        UUID reporter = UUID.randomUUID();
        IssueCategoryDto category = categoryService.create(new CreateIssueCategoryDto(
                "EDUCATION", "Elimu", null, "COUNCIL", 4320, 43200, false, false, "PUBLIC", "book"));
        ReportDto filed = reportService.fileReport(reporter, new FileReportDto(
                category.id(), "Madawati", "Hakuna madawati", wardId, null, null, "PUBLIC", false, null));

        CaseEventDto comment = reportService.addComment(reporter, filed.id(), "Nimeongeza picha");
        assertThat(comment.publicEvent()).isTrue();
        assertThat(comment.message()).isEqualTo("Nimeongeza picha");

        long timelineSize = reportService.getMyReportTimeline(reporter, filed.id(), page).getTotalElements();
        // Initial STATUS_CHANGE + the comment.
        assertThat(timelineSize).isGreaterThanOrEqualTo(2);
    }
}
