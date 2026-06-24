package com.taarifu.responders;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.FlywayCleanMigrateTestConfig;
import com.taarifu.responders.domain.model.enums.OrganisationStatus;
import com.taarifu.responders.domain.repository.OrganisationRepository;
import com.taarifu.responders.domain.repository.ResponderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers tests for the responders <b>Flyway migrations</b> V34/V35 and the DB-owned integrity
 * invariants (PRD §24, ARCHITECTURE.md §4.1/§4.3). Requires Docker — written for CI; may be skipped in
 * a Docker-less local build (the module's unit tests cover the Java guards).
 *
 * <p>Responsibility: unlike the create-drop slice tests, this test boots with <b>Flyway enabled +
 * {@code ddl-auto=validate}</b> (the production configuration) so it proves three things at once:
 * (1) the V34 DDL matches the JPA entities exactly (validate is green — the whole context loads);
 * (2) the V35 seed makes verified parastatals publicly listable through the repository; and
 * (3) the {@code ux_responder_assignment_one_owner} partial unique index actually rejects a second
 * live OWNER for a report (§24.3) — the integrity guarantee H2 cannot reproduce (ADR-0009).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FlywayCleanMigrateTestConfig.class)   // clean-then-migrate so create-drop leftovers in the shared container never block Flyway
@TestPropertySource(properties = {
        // Override the test profile's create-drop with the production schema path: Flyway owns the
        // schema and Hibernate only validates — so this test exercises the REAL migrations, not a
        // schema generated from entities.
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        // Allow Flyway.clean() in tests ONLY (production keeps clean disabled): the shared static container
        // is reused across create-drop and Flyway tests, so this test cleans first to start from an empty
        // schema and avoid Flyway's "non-empty schema, no history table" fail-safe (FlywayCleanMigrateTestConfig).
        "spring.flyway.clean-disabled=false"
})
class ResponderMigrationIntegrationTest extends AbstractPostgisIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private OrganisationRepository organisationRepository;

    @Autowired
    private ResponderRepository responderRepository;

    @Test
    @Transactional
    void seed_makesVerifiedParastatalsPubliclyListable() {
        // V35 seeds TANESCO + DAWASA as ACTIVE + verified; the public-directory query must return them.
        var page = organisationRepository.findByStatusAndVerifiedTrue(
                OrganisationStatus.ACTIVE, PageRequest.of(0, 20));
        assertThat(page.getContent()).extracting(o -> o.getName())
                .contains("TANESCO", "DAWASA");

        var responders = responderRepository.findPubliclyListable(PageRequest.of(0, 20));
        assertThat(responders.getContent()).isNotEmpty();
    }

    @Test
    @Transactional
    void secondLiveOwner_isRejectedByPartialUniqueIndex() {
        UUID reportId = UUID.randomUUID();
        long responderA = responderId("b0000000-0000-4000-8000-000000000001");
        long responderB = responderId("b0000000-0000-4000-8000-000000000002");

        insertAssignment(reportId, responderA, "OWNER");
        em.flush();

        // A SECOND live OWNER for the same report must violate ux_responder_assignment_one_owner (§24.3).
        assertThatThrownBy(() -> {
            insertAssignment(reportId, responderB, "OWNER");
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @Transactional
    void ownerPlusCollaborator_isAccepted() {
        UUID reportId = UUID.randomUUID();
        long responderA = responderId("b0000000-0000-4000-8000-000000000001");
        long responderB = responderId("b0000000-0000-4000-8000-000000000002");

        insertAssignment(reportId, responderA, "OWNER");
        insertAssignment(reportId, responderB, "COLLABORATOR");
        em.flush();

        Number count = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM responder_assignment WHERE report_id = :rid")
                .setParameter("rid", reportId)
                .getSingleResult();
        assertThat(count.longValue()).isEqualTo(2L);
    }

    @Test
    @Transactional
    void sameResponderTwiceOnReport_isRejectedByPartialUniqueIndex() {
        UUID reportId = UUID.randomUUID();
        long responderA = responderId("b0000000-0000-4000-8000-000000000001");

        insertAssignment(reportId, responderA, "OWNER");
        em.flush();

        // The same responder cannot hold two live assignments on one report (ux_..unique_per_report).
        assertThatThrownBy(() -> {
            insertAssignment(reportId, responderA, "COLLABORATOR");
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    private long responderId(String publicId) {
        return ((Number) em.createNativeQuery("SELECT id FROM responder WHERE public_id = :pid")
                .setParameter("pid", UUID.fromString(publicId))
                .getSingleResult()).longValue();
    }

    private void insertAssignment(UUID reportId, long responderId, String role) {
        em.createNativeQuery("""
                INSERT INTO responder_assignment
                    (public_id, version, created_at, deleted, report_id, responder_id, role, status, assigned_at)
                VALUES (:pid, 0, :now, false, :rid, :resp, :role, 'PENDING', :now)
                """)
                .setParameter("pid", UUID.randomUUID())
                .setParameter("now", Instant.now())
                .setParameter("rid", reportId)
                .setParameter("resp", responderId)
                .setParameter("role", role)
                .executeUpdate();
    }
}
