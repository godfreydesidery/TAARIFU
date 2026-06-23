package com.taarifu.media;

import com.taarifu.media.domain.model.MediaObject;
import com.taarifu.media.domain.model.enums.ScanStatus;
import com.taarifu.media.domain.repository.MediaObjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration test: proves the {@code V47__media.sql} Flyway migration matches the {@link MediaObject}
 * entity under Hibernate {@code ddl-auto=validate} (ARCHITECTURE.md §4.1, ADR-0005/0009).
 *
 * <p>Responsibility: this is the production-faithful path — it runs <b>all</b> Flyway migrations
 * (V1…V47) against a real PostGIS container and starts Hibernate in {@code validate} mode, so the
 * context only loads if every entity (including {@code media_object}) exactly agrees with its DDL. It
 * then persists and reloads a {@link MediaObject} to confirm the round-trip and the scan-state column
 * behave on real Postgres. WHY a dedicated test (the shared {@code test} profile disables Flyway and
 * uses create-drop): without this, a schema/entity drift in V47 would not be caught until production
 * boot — the integrity check this test exists to provide.</p>
 *
 * <p>WHY its own dedicated container (not the shared {@link AbstractPostgisIntegrationTest} one): Flyway
 * requires an empty schema to migrate from scratch; the shared container is polluted by other tests'
 * {@code create-drop} tables, so this test owns a pristine PostGIS container to run V1…V47 cleanly.</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
        // Override only the two test-profile defaults that differ from the PRODUCTION contract: turn
        // Flyway ON and Hibernate to validate (inlined @SpringBootTest properties win over the profile
        // yml). Everything else (JWT/crypto secrets, all stub adapters) comes from application-test.yml.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class MediaMigrationValidateIntegrationTest {

    /** A pristine PostGIS container owned solely by this test so Flyway migrates from an empty schema. */
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("taarifu_media_migr")
                    .withUsername("taarifu")
                    .withPassword("taarifu");

    static {
        POSTGIS.start();
    }

    @Autowired private MediaObjectRepository repository;

    /** Wires this test's dedicated container and an empty CORS allow-list into the context. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("taarifu.security.cors.allowed-origins", () -> "");
    }

    @Test
    @Transactional
    void migrationValidates_andMediaObjectRoundTrips() {
        // Reaching this line means Hibernate validate passed against the Flyway schema for ALL entities,
        // including media_object — i.e. V47 matches MediaObject exactly.
        MediaObject saved = repository.saveAndFlush(new MediaObject(
                "REPORT", UUID.randomUUID(), "quarantine/2026/06/" + UUID.randomUUID(),
                "evidence.jpg", "image/jpeg", 4096L, UUID.randomUUID()));

        MediaObject reloaded = repository.findByPublicId(saved.getPublicId()).orElseThrow();
        assertThat(reloaded.getScanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(reloaded.getObjectKey()).startsWith("quarantine/");
        assertThat(reloaded.isExifStripped()).isFalse();
        assertThat(reloaded.getPublicId()).isNotNull();
    }
}
