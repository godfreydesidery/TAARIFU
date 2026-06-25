package com.taarifu.payments;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
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
 * Migration↔entity agreement test for the payments module (CLAUDE.md §10; ADR-0005/0009/0015).
 *
 * <p>Responsibility: the payments IT that runs the <b>real Flyway migrations</b> (including this module's
 * {@code V130__payments_top_up.sql}) with Hibernate {@code ddl-auto=validate} — the exact production
 * configuration. If {@code V130}'s columns/constraints disagree with the {@link
 * com.taarifu.payments.domain.model.TopUp} mapping, the Spring context fails to start and this test goes
 * red. It is the guard {@code create-drop} cannot give: proof the hand-written DDL and the entity are in
 * lockstep, and that the whole context (with the payments beans) boots on the logging stub with zero
 * external calls.</p>
 *
 * <p>WHY a dedicated pristine container (not the shared one): Flyway refuses to run against a non-empty,
 * historyless schema left by other ITs' {@code create-drop}; a fresh container guarantees a clean apply
 * (the established pattern, mirroring the accountability migration IT).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PaymentsMigrationValidateIntegrationTest {

    /** A pristine PostGIS container exclusive to this class so Flyway applies against an empty schema. */
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> PRISTINE_POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("taarifu_payments_migrate_test")
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
    void migrationsApplyAndTopUpEntityValidatesAgainstThem() {
        // If the context started, Flyway applied V130 + V131 and Hibernate validated TopUp against the
        // migrated schema. A spot-check confirms the table exists and is queryable.
        Number topUps = (Number) em.createNativeQuery("SELECT count(*) FROM top_up").getSingleResult();
        assertThat(topUps.longValue()).isZero();
    }

    /**
     * Proves V131 (REFUND/VOID addendum) applied: the two new columns exist and the widened status CHECK
     * admits VOIDED/REFUNDED. If V131 had not run (or disagreed with the entity), the context would not have
     * started and this class would be red before reaching here.
     */
    @Test
    @Transactional
    void v131RefundVoidColumnsAndConstraintExist() {
        // The two new columns are present (the context already validated the entity mapping against them).
        Number cols = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM information_schema.columns "
                                + "WHERE table_name = 'top_up' "
                                + "AND column_name IN ('reversal_event_id','reversal_reason')")
                .getSingleResult();
        assertThat(cols.intValue()).isEqualTo(2);

        // The widened status CHECK admits a REFUNDED row (would violate ck_top_up_status if V131 had not run)
        // and the reversal-event-id binding (ck_top_up_reversal_event_id) is satisfied when set together.
        em.createNativeQuery(
                        "INSERT INTO top_up (public_id, version, buyer_id, wallet_owner_type, provider, "
                                + "provider_ref, amount_minor, token_amount, currency, status, idempotency_key, "
                                + "reversal_event_id, reversal_reason) "
                                + "VALUES (:pid, 0, :buyer, 'USER', 'MPESA', :ref, 1000, 10, 'TZS', "
                                + "'REFUNDED', :idem, :rev, 'DUPLICATE_CHARGE')")
                .setParameter("pid", UUID.randomUUID())
                .setParameter("buyer", UUID.randomUUID())
                .setParameter("ref", "REF-" + UUID.randomUUID())
                .setParameter("idem", "idem-" + UUID.randomUUID())
                .setParameter("rev", UUID.randomUUID())
                .executeUpdate();

        Number refunded = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM top_up WHERE status = 'REFUNDED'")
                .getSingleResult();
        assertThat(refunded.intValue()).isEqualTo(1);
    }
}
