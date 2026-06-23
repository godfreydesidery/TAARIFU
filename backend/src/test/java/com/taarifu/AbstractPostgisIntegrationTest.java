package com.taarifu;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that need a real <b>PostgreSQL + PostGIS</b> database (ADR-0009,
 * ARCHITECTURE.md §4.1).
 *
 * <p>Responsibility: starts a single PostGIS Testcontainer (shared across all subclasses via a static
 * container for speed) and wires its JDBC URL/credentials into the Spring context with
 * {@link DynamicPropertySource}. Tests run against the <b>same PostGIS image family as production</b>
 * so geometry columns, partial-unique indexes, and constraint behaviour are faithful — H2 cannot
 * reproduce these (ADR-0009).</p>
 *
 * <p>WHY a {@code postgis/postgis} image (not plain {@code postgres}): the {@code geometry} columns on
 * {@code location} require the PostGIS extension to exist before Hibernate's {@code create-drop}
 * generates DDL. This image ships PostGIS preinstalled and enabled in the default database, so no manual
 * {@code CREATE EXTENSION} step is needed for the test schema.</p>
 *
 * <p>WHY a static singleton container (not per-class): starting PostGIS is the slow part; sharing one
 * container across the suite keeps integration tests fast while remaining hermetic (ADR-0009 cost note).
 * Testcontainers/Ryuk reaps it at JVM exit.</p>
 */
@Testcontainers
public abstract class AbstractPostgisIntegrationTest {

    /** Shared PostGIS container for the whole integration-test suite. */
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("taarifu_test")
                    .withUsername("taarifu")
                    .withPassword("taarifu");

    static {
        // Manual lifecycle (not @Container) so the container is a singleton reused by every subclass.
        POSTGIS.start();
    }

    /**
     * Injects the running container's connection details into the Spring context.
     *
     * @param registry the dynamic property registry.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
    }
}
