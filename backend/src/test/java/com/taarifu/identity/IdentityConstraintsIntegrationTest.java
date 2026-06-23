package com.taarifu.identity;

import com.taarifu.AbstractPostgisIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers tests for the identity hard integrity constraints (FOUNDATION-SCOPE.md §5/§6, D11/D15).
 *
 * <p>Responsibility: proves the database actually enforces the one-person-one-account invariants the
 * civic integrity of Taarifu depends on — a <b>unique phone</b> (D11/D15) and a <b>unique blind-index
 * {@code id_hash}</b> for ID dedup (D15). These are integration tests because the guarantees live in
 * Postgres unique indexes, not in Java — exactly the behaviour ADR-0009 says H2 cannot reproduce.</p>
 *
 * <p>WHY native inserts: the production entities are read-only by design; the constraint under test is a
 * DB index, so inserting rows directly is the most faithful way to assert the index fires. The encrypted
 * {@code id_no} column is irrelevant here — dedup is by {@code id_hash} only (it never decrypts, D15).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class IdentityConstraintsIntegrationTest extends AbstractPostgisIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Test
    @Transactional
    void duplicatePhone_isRejectedByUniqueIndex() {
        insertUser("+255700000001");

        // Inserting a second account with the same phone must violate ux_app_user_phone (D11/D15).
        assertThatThrownBy(() -> {
            insertUser("+255700000001");
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @Transactional
    void distinctPhones_areAccepted() {
        insertUser("+255700000002");
        insertUser("+255700000003");
        em.flush();

        Number count = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM app_user WHERE phone IN ('+255700000002','+255700000003')")
                .getSingleResult();
        assertThat(count.longValue()).isEqualTo(2L);
    }

    @Test
    @Transactional
    void duplicateIdHash_isRejectedByUniqueIndex() {
        long user1 = insertUser("+255700000004");
        long user2 = insertUser("+255700000005");
        insertProfile(user1, "HASH-DEDUP-1");

        // A second profile with the same blind-index hash must violate ux_profile_id_hash (D15).
        assertThatThrownBy(() -> {
            insertProfile(user2, "HASH-DEDUP-1");
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    private long insertUser(String phone) {
        UUID pid = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO app_user (public_id, version, created_at, deleted, phone, status, trust_tier, mfa_enabled)
                VALUES (:pid, 0, :now, false, :phone, 'PENDING', 'T0', false)
                """)
                .setParameter("pid", pid)
                .setParameter("now", Instant.now())
                .setParameter("phone", phone)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM app_user WHERE public_id = :pid")
                .setParameter("pid", pid).getSingleResult()).longValue();
    }

    private void insertProfile(long userId, String idHash) {
        em.createNativeQuery("""
                INSERT INTO profile (public_id, version, created_at, deleted, user_id, type,
                                     id_hash, id_verified, email_verified, phone_verified)
                VALUES (:pid, 0, :now, false, :user, 'PERSON', :hash, false, false, false)
                """)
                .setParameter("pid", UUID.randomUUID())
                .setParameter("now", Instant.now())
                .setParameter("user", userId)
                .setParameter("hash", idHash)
                .executeUpdate();
    }
}
