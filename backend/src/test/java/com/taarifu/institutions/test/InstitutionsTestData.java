package com.taarifu.institutions.test;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Test-only seed helper for the institutions slice (ADR-0009).
 *
 * <p>Responsibility: builds a minimal but realistic fixture — a party (CCM), a current Union Parliament
 * term, and the geography rows (Kilimanjaro→Rombo→Council→Mengwe, the Rombo constituency, and a current
 * ward→constituency mapping) — so the institutions read endpoints, find-my-rep, and the DB invariants can
 * be exercised end-to-end against a real Postgres.</p>
 *
 * <p>WHY native SQL inserts (not the entities): production entities are read-only from the outside; rather
 * than weaken that for tests, this seeds rows directly — exactly how the real Flyway seed will load
 * reference data. It populates the audit/version/soft-delete columns the {@code BaseEntity} schema
 * requires (mirrors {@code GeographyTestData}).</p>
 */
@Component
public class InstitutionsTestData {

    @PersistenceContext
    private EntityManager em;

    /** Public ids + internal ids of the seeded fixture, returned for assertions/inserts. */
    public record Fixture(UUID wardPublicId, long wardId,
                          UUID constituencyPublicId, long constituencyId,
                          UUID partyPublicId, long partyId,
                          UUID parliamentPublicId, long parliamentId) {
    }

    /** Removes all institutions + geography rows so each test starts clean. */
    @Transactional
    public void clear() {
        em.createNativeQuery("DELETE FROM representative").executeUpdate();
        em.createNativeQuery("DELETE FROM parliament_role").executeUpdate();
        em.createNativeQuery("DELETE FROM parliament").executeUpdate();
        em.createNativeQuery("DELETE FROM political_party").executeUpdate();
        em.createNativeQuery("DELETE FROM ward_constituency").executeUpdate();
        em.createNativeQuery("DELETE FROM location_closure").executeUpdate();
        em.createNativeQuery("DELETE FROM constituency").executeUpdate();
        em.createNativeQuery("DELETE FROM location").executeUpdate();
    }

    /** Seeds the geography + party + current parliament fixture. */
    @Transactional
    public Fixture seed() {
        long region = insertLocation("TZ-19", "Kilimanjaro", "REGION", null);
        long district = insertLocation("TZ-1907", "Rombo", "DISTRICT", region);
        long council = insertLocation("TZ-1907-LGA", "Rombo District Council", "COUNCIL", district);
        long ward = insertLocation("TZ-1907-WD-MENGWE", "Mengwe", "WARD", council);

        long constituency = insertConstituency("TZ-JIMBO-ROMBO", "Rombo", district);
        insertWardConstituency(ward, constituency);

        long party = insertParty("CCM", "Chama Cha Mapinduzi", "CCM");
        long parliament = insertParliament(12, "12th Parliament", "UNION_PARLIAMENT", true);

        return new Fixture(
                publicId("location", ward), ward,
                publicId("constituency", constituency), constituency,
                publicId("political_party", party), party,
                publicId("parliament", parliament), parliament);
    }

    private long insertLocation(String code, String name, String type, Long parentId) {
        UUID pid = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO location (public_id, version, created_at, deleted, code, name, type, status, parent_id)
                VALUES (:pid, 0, :now, false, :code, :name, :type, 'ACTIVE', :parent)
                """)
                .setParameter("pid", pid).setParameter("now", Instant.now())
                .setParameter("code", code).setParameter("name", name)
                .setParameter("type", type).setParameter("parent", parentId)
                .executeUpdate();
        return idOf("location", pid);
    }

    private long insertConstituency(String code, String name, long districtId) {
        UUID pid = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO constituency (public_id, version, created_at, deleted, code, name, district_id)
                VALUES (:pid, 0, :now, false, :code, :name, :district)
                """)
                .setParameter("pid", pid).setParameter("now", Instant.now())
                .setParameter("code", code).setParameter("name", name).setParameter("district", districtId)
                .executeUpdate();
        return idOf("constituency", pid);
    }

    private void insertWardConstituency(long wardId, long constituencyId) {
        em.createNativeQuery("""
                INSERT INTO ward_constituency
                    (public_id, version, created_at, deleted, ward_id, constituency_id, effective_from, effective_to)
                VALUES (:pid, 0, :now, false, :ward, :constituency, DATE '2020-01-01', NULL)
                """)
                .setParameter("pid", UUID.randomUUID()).setParameter("now", Instant.now())
                .setParameter("ward", wardId).setParameter("constituency", constituencyId)
                .executeUpdate();
    }

    private long insertParty(String code, String name, String abbrev) {
        UUID pid = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO political_party (public_id, version, created_at, deleted, code, name, abbreviation, status)
                VALUES (:pid, 0, :now, false, :code, :name, :abbrev, 'ACTIVE')
                """)
                .setParameter("pid", pid).setParameter("now", Instant.now())
                .setParameter("code", code).setParameter("name", name).setParameter("abbrev", abbrev)
                .executeUpdate();
        return idOf("political_party", pid);
    }

    private long insertParliament(int term, String name, String legislature, boolean current) {
        UUID pid = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO parliament (public_id, version, created_at, deleted, term_number, name, legislature, start_date, is_current)
                VALUES (:pid, 0, :now, false, :term, :name, :leg, DATE '2020-11-01', :current)
                """)
                .setParameter("pid", pid).setParameter("now", Instant.now())
                .setParameter("term", term).setParameter("name", name).setParameter("leg", legislature)
                .setParameter("current", current)
                .executeUpdate();
        return idOf("parliament", pid);
    }

    /**
     * Inserts a representative directly (used to seed/contend the DB invariants in integration tests).
     *
     * @param type            RepresentativeType name.
     * @param mandate         Mandate name.
     * @param constituencyId  constituency FK or {@code null}.
     * @param wardId          ward FK or {@code null}.
     * @param status          RepresentativeStatus name.
     * @return the inserted row's internal id.
     */
    @Transactional
    public long insertRepresentative(String type, String mandate, Long constituencyId, Long wardId,
                                     String status) {
        UUID pid = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO representative
                    (public_id, version, created_at, deleted, profile_id, type, mandate,
                     constituency_id, ward_id, legislature, status)
                VALUES (:pid, 0, :now, false, :profile, :type, :mandate,
                        :constituency, :ward, 'UNION_PARLIAMENT', :status)
                """)
                .setParameter("pid", pid).setParameter("now", Instant.now())
                .setParameter("profile", UUID.randomUUID())
                .setParameter("type", type).setParameter("mandate", mandate)
                .setParameter("constituency", constituencyId).setParameter("ward", wardId)
                .setParameter("status", status)
                .executeUpdate();
        return idOf("representative", pid);
    }

    /**
     * Inserts a second {@code is_current = true} UNION_PARLIAMENT term to contend the single-current
     * invariant (used by the migration invariants test). The partial-unique index must reject it.
     *
     * @return the inserted row's internal id (never reached if the index is in force).
     */
    @Transactional
    public long insertCurrentParliamentDuplicate() {
        return insertParliament(13, "13th Parliament (contender)", "UNION_PARLIAMENT", true);
    }

    private long idOf(String table, UUID pid) {
        return ((Number) em.createNativeQuery("SELECT id FROM " + table + " WHERE public_id = :pid")
                .setParameter("pid", pid).getSingleResult()).longValue();
    }

    private UUID publicId(String table, long id) {
        return (UUID) em.createNativeQuery("SELECT public_id FROM " + table + " WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }
}
