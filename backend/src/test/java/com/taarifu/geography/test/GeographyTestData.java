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
     * Public ids of the richer ward-picker fixture (two districts, several wards incl. a same-named pair),
     * returned for assertions by {@link #seedWardPickerScenario()}.
     *
     * @param romboDistrictId     Rombo (Wilaya) public id — has 2 wards (Mengwe, Mahida).
     * @param kinondoniDistrictId Kinondoni (Wilaya) public id — has 2 wards (Mwananyamala, Mahida).
     * @param romboCouncilName    name of Rombo's council (for council-name assertions).
     * @param mengweWardId        Mengwe ward public id (Rombo).
     * @param romboMahidaWardId   "Mahida" ward in Rombo (same name as the Kinondoni one — disambiguation).
     * @param mwananyamalaWardId  Mwananyamala ward public id (Kinondoni).
     */
    public record WardPickerFixture(UUID romboDistrictId, UUID kinondoniDistrictId, String romboCouncilName,
                                    UUID mengweWardId, UUID romboMahidaWardId, UUID mwananyamalaWardId) {
    }

    /**
     * Removes all geography rows so each test starts clean.
     *
     * <p>WHY {@code profile_location}/{@code representative} are cleared first: both carry an FK to
     * {@code constituency}/{@code location}. If a test that <b>committed</b> such a row (an identity electoral
     * location, or an institutions representative) ran earlier against the shared static container, the
     * {@code DELETE FROM constituency}/{@code location} below would fail with a foreign-key violation —
     * coupling this geography fixture to test order. Clearing the referencing rows first keeps the wipe robust
     * regardless of order (TEST-ONLY reset; no production code, no production behaviour touched).</p>
     */
    @Transactional
    public void clear() {
        // Drop any leaked cross-module rows that reference geography before wiping geography itself.
        em.createNativeQuery("DELETE FROM profile_location").executeUpdate();
        em.createNativeQuery("DELETE FROM representative").executeUpdate();
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

    /**
     * Seeds a richer two-district scenario for the manual ward-picker tests: Kilimanjaro→Rombo (council)
     * with wards {@code Mengwe} + {@code Mahida}, and Dar es Salaam→Kinondoni (council) with wards
     * {@code Mwananyamala} + {@code Mahida}. The duplicated {@code Mahida} name across districts exercises
     * council/district-name disambiguation and district-scoped search.
     *
     * <p>Each ward gets its full closure chain (region→district→council→ward, plus self-pairs) so the
     * closure-backed district→wards listing and the ancestor-name resolution are exercised exactly as in
     * production (the closure table is the only path to "all wards under a district").</p>
     *
     * @return the public ids needed for assertions.
     */
    @Transactional
    public WardPickerFixture seedWardPickerScenario() {
        // Kilimanjaro → Rombo → council → {Mengwe, Mahida}
        long kiliRegion = insertLocation("TZ-19", "Kilimanjaro", "REGION", null);
        insertClosure(kiliRegion, kiliRegion, 0);
        long rombo = insertLocation("TZ-1907", "Rombo", "DISTRICT", kiliRegion);
        insertClosure(rombo, rombo, 0);
        insertClosure(kiliRegion, rombo, 1);
        String romboCouncilName = "Rombo District Council";
        long romboCouncil = insertLocation("TZ-1907-LGA", romboCouncilName, "COUNCIL", rombo);
        insertClosure(romboCouncil, romboCouncil, 0);
        insertClosure(rombo, romboCouncil, 1);
        insertClosure(kiliRegion, romboCouncil, 2);
        long mengwe = insertWardWithClosure("TZ-1907-WD-MENGWE", "Mengwe", romboCouncil, rombo, kiliRegion);
        long romboMahida = insertWardWithClosure("TZ-1907-WD-MAHIDA", "Mahida", romboCouncil, rombo, kiliRegion);

        // Dar es Salaam → Kinondoni → council → {Mwananyamala, Mahida}
        long darRegion = insertLocation("TZ-07", "Dar es Salaam", "REGION", null);
        insertClosure(darRegion, darRegion, 0);
        long kinondoni = insertLocation("TZ-0701", "Kinondoni", "DISTRICT", darRegion);
        insertClosure(kinondoni, kinondoni, 0);
        insertClosure(darRegion, kinondoni, 1);
        long kinondoniCouncil = insertLocation("TZ-0701-LGA", "Kinondoni Municipal Council", "COUNCIL", kinondoni);
        insertClosure(kinondoniCouncil, kinondoniCouncil, 0);
        insertClosure(kinondoni, kinondoniCouncil, 1);
        insertClosure(darRegion, kinondoniCouncil, 2);
        long mwananyamala = insertWardWithClosure("TZ-0701-WD-MWANANYAMALA", "Mwananyamala",
                kinondoniCouncil, kinondoni, darRegion);
        insertWardWithClosure("TZ-0701-WD-MAHIDA", "Mahida", kinondoniCouncil, kinondoni, darRegion);

        return new WardPickerFixture(
                publicId(rombo), publicId(kinondoni), romboCouncilName,
                publicId(mengwe), publicId(romboMahida), publicId(mwananyamala));
    }

    /**
     * Inserts a {@code WARD} under the given council and writes its full closure chain
     * (self-pair + council→ward, district→ward, region→ward).
     *
     * @return the new ward's internal id.
     */
    private long insertWardWithClosure(String code, String name, long councilId, long districtId, long regionId) {
        long ward = insertLocation(code, name, "WARD", councilId);
        insertClosure(ward, ward, 0);
        insertClosure(councilId, ward, 1);
        insertClosure(districtId, ward, 2);
        insertClosure(regionId, ward, 3);
        return ward;
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
