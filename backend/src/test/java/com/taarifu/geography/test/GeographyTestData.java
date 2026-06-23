package com.taarifu.geography.test;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Test-only seed helper for the geography slice (ADR-0009).
 *
 * <p>Responsibility: builds a tiny but realistic Tanzanian fixture — Region <i>Kilimanjaro</i> →
 * District <i>Rombo</i> → Council → Ward <i>Mengwe</i>, the constituency <i>Rombo</i>, the closure-table
 * rows, and a <b>current</b> (effective_to = null) ward→constituency mapping — so the read endpoints,
 * the closure ancestor query, and the effective-dated resolution can be exercised end-to-end.</p>
 *
 * <p>WHY native SQL inserts (not the entities): production entities are deliberately read-only (no
 * setters, protected constructors) to keep the domain immutable from the outside. Rather than weaken
 * that for tests, this helper inserts rows directly — which is exactly how the real Flyway seed
 * (EI-14) will load reference data anyway. It populates the audit/version/soft-delete columns the
 * {@code BaseEntity} schema requires.</p>
 */
@Component
public class GeographyTestData {

    @PersistenceContext
    private EntityManager em;

    /** Public ids of the seeded fixture, returned for assertions. */
    public record Fixture(UUID regionPublicId, UUID districtPublicId, UUID councilPublicId,
                          UUID wardPublicId, UUID constituencyPublicId) {
    }

    /**
     * Removes all geography rows so each test starts clean.
     */
    @Transactional
    public void clear() {
        em.createNativeQuery("DELETE FROM ward_constituency").executeUpdate();
        em.createNativeQuery("DELETE FROM location_closure").executeUpdate();
        em.createNativeQuery("DELETE FROM constituency").executeUpdate();
        em.createNativeQuery("DELETE FROM location").executeUpdate();
    }

    /**
     * Seeds the Kilimanjaro→Rombo→Council→Mengwe hierarchy plus the Rombo constituency and a current
     * ward mapping.
     *
     * @return the public ids of the seeded rows.
     */
    @Transactional
    public Fixture seedKilimanjaroRomboMengwe() {
        long region = insertLocation("TZ-19", "Kilimanjaro", "REGION", null);
        long district = insertLocation("TZ-1907", "Rombo", "DISTRICT", region);
        long council = insertLocation("TZ-1907-LGA", "Rombo District Council", "COUNCIL", district);
        long ward = insertLocation("TZ-1907-WD-MENGWE", "Mengwe", "WARD", council);

        // Closure rows: self-pairs (depth 0) + every ancestor→descendant pair.
        insertClosure(region, region, 0);
        insertClosure(district, district, 0);
        insertClosure(council, council, 0);
        insertClosure(ward, ward, 0);
        insertClosure(region, district, 1);
        insertClosure(region, council, 2);
        insertClosure(region, ward, 3);
        insertClosure(district, council, 1);
        insertClosure(district, ward, 2);
        insertClosure(council, ward, 1);

        long constituency = insertConstituency("TZ-JIMBO-ROMBO", "Rombo", district);
        // Current mapping: effective from a past date, open-ended (effective_to NULL).
        insertWardConstituency(ward, constituency);

        return new Fixture(
                publicId(region), publicId(district), publicId(council),
                publicId(ward), constituencyPublicId(constituency));
    }

    private long insertLocation(String code, String name, String type, Long parentId) {
        UUID pid = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO location (public_id, version, created_at, deleted, code, name, type, status, parent_id)
                VALUES (:pid, 0, :now, false, :code, :name, :type, 'ACTIVE', :parent)
                """)
                .setParameter("pid", pid)
                .setParameter("now", Instant.now())
                .setParameter("code", code)
                .setParameter("name", name)
                .setParameter("type", type)
                .setParameter("parent", parentId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM location WHERE public_id = :pid")
                .setParameter("pid", pid).getSingleResult()).longValue();
    }

    private void insertClosure(long ancestorId, long descendantId, int depth) {
        em.createNativeQuery("""
                INSERT INTO location_closure (public_id, version, created_at, deleted, ancestor_id, descendant_id, depth)
                VALUES (:pid, 0, :now, false, :anc, :desc, :depth)
                """)
                .setParameter("pid", UUID.randomUUID())
                .setParameter("now", Instant.now())
                .setParameter("anc", ancestorId)
                .setParameter("desc", descendantId)
                .setParameter("depth", depth)
                .executeUpdate();
    }

    private long insertConstituency(String code, String name, long districtId) {
        UUID pid = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO constituency (public_id, version, created_at, deleted, code, name, district_id)
                VALUES (:pid, 0, :now, false, :code, :name, :district)
                """)
                .setParameter("pid", pid)
                .setParameter("now", Instant.now())
                .setParameter("code", code)
                .setParameter("name", name)
                .setParameter("district", districtId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM constituency WHERE public_id = :pid")
                .setParameter("pid", pid).getSingleResult()).longValue();
    }

    private void insertWardConstituency(long wardId, long constituencyId) {
        em.createNativeQuery("""
                INSERT INTO ward_constituency
                    (public_id, version, created_at, deleted, ward_id, constituency_id, effective_from, effective_to)
                VALUES (:pid, 0, :now, false, :ward, :constituency, DATE '2020-01-01', NULL)
                """)
                .setParameter("pid", UUID.randomUUID())
                .setParameter("now", Instant.now())
                .setParameter("ward", wardId)
                .setParameter("constituency", constituencyId)
                .executeUpdate();
    }

    private UUID publicId(long locationId) {
        return (UUID) em.createNativeQuery("SELECT public_id FROM location WHERE id = :id")
                .setParameter("id", locationId).getSingleResult();
    }

    private UUID constituencyPublicId(long constituencyId) {
        return (UUID) em.createNativeQuery("SELECT public_id FROM constituency WHERE id = :id")
                .setParameter("id", constituencyId).getSingleResult();
    }
}
