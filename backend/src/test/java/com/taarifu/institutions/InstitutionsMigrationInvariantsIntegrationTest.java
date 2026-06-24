package com.taarifu.institutions;

import com.taarifu.institutions.test.InstitutionsTestData;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers test that runs the real <b>Flyway migrations</b> (V1–V21) with
 * {@code ddl-auto=validate} and proves (a) the entities match the migrated schema, and (b) the
 * DB-owned integrity invariants the JPA model cannot express actually fire (ADR-0005/0009). Requires
 * Docker — written for CI; local-without-Docker runs skip/fail at container start, which is acceptable
 * per the build instructions (unit tests + compile are the local gate).
 *
 * <p>WHY a dedicated, isolated container (not the shared {@code AbstractPostgisIntegrationTest} one) plus
 * {@code @DirtiesContext}: every other integration test generates schema from entities (create-drop) on
 * the shared container; running Flyway against that already-populated database would collide. This class
 * provisions its OWN fresh PostGIS container and a fresh Spring context so Flyway migrates a clean
 * database exactly as production does.</p>
 *
 * <p>WHY it exists separately from the read test: the {@code test} profile's create-drop would NOT
 * exercise the partial-unique indexes and CHECK constraints declared only in the Flyway SQL. Here Flyway
 * is on + validate (matching production), so those invariants are tested against the migration that ships:</p>
 * <ul>
 *   <li>one SITTING constituency-MP per constituency (ux_representative_sitting_constituency);</li>
 *   <li>mandate ⇄ geography coherence (ck_representative_mandate_geo);</li>
 *   <li>single current parliament term per legislature (ux_parliament_current_per_legislature).</li>
 * </ul>
 * <p>{@code validate} passing at all is itself the migration↔entity agreement assertion.</p>
 *
 * <p><b>WHY the assertions expect {@link ConstraintViolationException} (not Spring's
 * {@code DataIntegrityViolationException}).</b> The fixture exercises the indexes/CHECKs by inserting rows
 * with the {@code EntityManager}'s <b>native</b> queries (the entities are read-only by design). A native
 * {@code executeUpdate}/{@code flush} that trips a DB constraint surfaces Hibernate's
 * {@code org.hibernate.exception.ConstraintViolationException} directly — Spring's persistence-exception
 * translation only wraps it into {@code DataIntegrityViolationException} for {@code @Repository}-managed
 * paths, which these native inserts are not. The original {@code DataIntegrityViolationException}
 * expectation therefore never matched (the constraint <i>did</i> fire — only the wrapper differed); the
 * other native-EM constraint ITs in the suite assert the raw type for the same reason.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class InstitutionsMigrationInvariantsIntegrationTest {

    /** Dedicated, isolated PostGIS container so Flyway migrates a clean database (see class Javadoc). */
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> MIGRATED_DB =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("taarifu_migration_test")
                    .withUsername("taarifu")
                    .withPassword("taarifu");

    static {
        MIGRATED_DB.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MIGRATED_DB::getJdbcUrl);
        registry.add("spring.datasource.username", MIGRATED_DB::getUsername);
        registry.add("spring.datasource.password", MIGRATED_DB::getPassword);
    }

    @Autowired
    private InstitutionsTestData testData;

    private InstitutionsTestData.Fixture fixture;

    @BeforeEach
    void seed() {
        testData.clear();
        fixture = testData.seed();
    }

    @Test
    void oneSittingMpPerConstituency_isEnforcedByPartialUniqueIndex() {
        // First SITTING MP on the constituency — accepted.
        testData.insertRepresentative("MP", "CONSTITUENCY", fixture.constituencyId(), null, "SITTING");

        // Second SITTING MP on the SAME constituency — must be rejected by ux_representative_sitting_constituency.
        assertThatThrownBy(() ->
                testData.insertRepresentative("MP", "CONSTITUENCY", fixture.constituencyId(), null, "SITTING"))
                .isInstanceOf(ConstraintViolationException.class);

        // A FORMER MP on the same constituency does NOT consume the slot — accepted.
        long formerId = testData.insertRepresentative("MP", "CONSTITUENCY", fixture.constituencyId(), null, "FORMER");
        assertThat(formerId).isPositive();
    }

    @Test
    void mandateGeographyCoherence_isEnforcedByCheckConstraint() {
        // CONSTITUENCY mandate with NO constituency violates ck_representative_mandate_geo.
        assertThatThrownBy(() ->
                testData.insertRepresentative("MP", "CONSTITUENCY", null, null, "PENDING_VERIFICATION"))
                .isInstanceOf(ConstraintViolationException.class);

        // SPECIAL_SEATS mandate carrying a constituency violates the same CHECK.
        assertThatThrownBy(() ->
                testData.insertRepresentative("MP", "SPECIAL_SEATS", fixture.constituencyId(), null, "PENDING_VERIFICATION"))
                .isInstanceOf(ConstraintViolationException.class);

        // SPECIAL_SEATS with neither geographic FK is VALID (Viti Maalum / nominated).
        long specialSeat = testData.insertRepresentative("MP", "SPECIAL_SEATS", null, null, "SITTING");
        assertThat(specialSeat).isPositive();
    }

    @Test
    void singleCurrentParliamentPerLegislature_isEnforcedByPartialUniqueIndex() {
        // The fixture already seeded one current UNION_PARLIAMENT term; a second current term must fail.
        assertThatThrownBy(() -> testData.insertCurrentParliamentDuplicate())
                .isInstanceOf(ConstraintViolationException.class);
    }
}
