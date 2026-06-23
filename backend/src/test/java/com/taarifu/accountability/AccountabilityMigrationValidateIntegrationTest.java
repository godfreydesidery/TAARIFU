package com.taarifu.accountability;

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
 * Migration↔entity agreement test for the accountability module (CLAUDE.md §10; ADR-0005/0009).
 *
 * <p>Responsibility: the only accountability IT that runs the <b>real Flyway migrations</b> (V1..V46,
 * including this module's V43–V46) with Hibernate {@code ddl-auto=validate} — the exact production
 * configuration. If a migration column/constraint disagrees with a JPA entity mapping, the Spring
 * context fails to start and this test goes red. It is the guard {@code create-drop} (used by the other
 * ITs for speed) cannot give: proof the hand-written DDL and the entities are in lockstep.</p>
 *
 * <p>WHY a DEDICATED, pristine container here (not the shared {@code AbstractPostgisIntegrationTest}
 * one): the shared container is reused across the suite and other ITs populate it via {@code create-drop},
 * leaving a non-empty {@code public} schema with no Flyway history — Flyway then refuses to run. A fresh
 * container per this class guarantees Flyway applies the migrations against an empty database, which is
 * the only faithful way to validate a clean apply. This class overrides the shared profile to Flyway-on +
 * {@code validate} for itself only, without touching shared config (module isolation).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AccountabilityMigrationValidateIntegrationTest {

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
        // If the context started, Flyway applied V43–V46 and Hibernate validated every accountability
        // entity against the migrated schema. A spot-check confirms the tables exist and are queryable.
        Number contributions = (Number) em.createNativeQuery(
                "SELECT count(*) FROM representative_contribution").getSingleResult();
        Number attendance = (Number) em.createNativeQuery(
                "SELECT count(*) FROM attendance").getSingleResult();
        Number promises = (Number) em.createNativeQuery("SELECT count(*) FROM promise").getSingleResult();
        Number ratings = (Number) em.createNativeQuery("SELECT count(*) FROM rating").getSingleResult();

        assertThat(contributions.longValue()).isZero();
        assertThat(attendance.longValue()).isZero();
        assertThat(promises.longValue()).isZero();
        assertThat(ratings.longValue()).isZero();
    }
}
