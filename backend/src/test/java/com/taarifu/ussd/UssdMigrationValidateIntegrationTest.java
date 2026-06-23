package com.taarifu.ussd;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration↔entity agreement test for the ussd module (CLAUDE.md §10; ADR-0005/0009).
 *
 * <p>Responsibility: the ussd IT that runs the <b>real Flyway migrations</b> (V1..V95, including this
 * module's V94/V95) with Hibernate {@code ddl-auto=validate} — the exact production configuration. If a
 * migration column/constraint disagrees with the {@code UssdSession}/{@code UssdAlertSubscription} entity
 * mappings, the Spring context fails to start and this test goes red. It is the guard {@code create-drop}
 * cannot give: proof the hand-written DDL and the entities are in lockstep.</p>
 *
 * <p>WHY a pristine container exclusive to this class: Flyway must apply against an empty schema; the
 * shared suite container is populated by {@code create-drop} ITs and has no Flyway history. This mirrors
 * the established module pattern and touches no shared config (module isolation). Requires Docker — it runs
 * in CI, not in the local unit phase.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class UssdMigrationValidateIntegrationTest {

    /** A pristine PostGIS container exclusive to this class so Flyway applies against an empty schema. */
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> PRISTINE_POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("taarifu_migrate_test")
                    .withUsername("taarifu")
                    .withPassword("taarifu");

    static {
        PRISTINE_POSTGIS.start();
    }

    /** Points the context at the pristine container and flips Flyway on + Hibernate to validate-only. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PRISTINE_POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", PRISTINE_POSTGIS::getUsername);
        registry.add("spring.datasource.password", PRISTINE_POSTGIS::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @PersistenceContext
    private EntityManager em;

    @Test
    @Transactional
    void migrationsApplyAndEntitiesValidateAgainstThem() {
        // If the context started, Flyway applied V94/V95 and Hibernate validated the ussd entities against
        // the migrated schema. A spot-check confirms the tables exist and are queryable.
        Number sessions = (Number) em.createNativeQuery("SELECT count(*) FROM ussd_session").getSingleResult();
        Number alerts = (Number) em.createNativeQuery(
                "SELECT count(*) FROM ussd_alert_subscription").getSingleResult();

        assertThat(sessions.longValue()).isZero();
        assertThat(alerts.longValue()).isZero();
    }
}
