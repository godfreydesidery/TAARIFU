package com.taarifu.reporting.test;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportPriority;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.model.enums.RoutingLevel;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

/**
 * Test-only builders for reporting entities (unit tests).
 *
 * <p>Responsibility: constructs {@link IssueCategory}/{@link Report} instances with a {@code publicId}
 * assigned (which production code sets on {@code @PrePersist}) so unit tests that map entities to DTOs or
 * match by public id have realistic objects without a database. Reflection is confined to this test
 * helper — production code never reaches into {@link BaseEntity}'s private fields.</p>
 */
public final class ReportingTestFixtures {

    private ReportingTestFixtures() {
    }

    /**
     * Builds a non-sensitive, PUBLIC-default category with a public id assigned.
     *
     * @param code category code.
     * @return the category.
     */
    public static IssueCategory publicCategory(String code) {
        IssueCategory c = new IssueCategory(code, "Maji na Usafi", null, RoutingLevel.SECTOR_UTILITY,
                2880, 20160, false, false, ReportVisibility.PUBLIC, "water-drop");
        assignPublicId(c, UUID.randomUUID());
        return c;
    }

    /**
     * Builds a sensitive, force-PRIVATE category (e.g. GBV/Corruption) with a public id assigned.
     *
     * @param code category code.
     * @return the category.
     */
    public static IssueCategory sensitiveForcedPrivateCategory(String code) {
        IssueCategory c = new IssueCategory(code, "Rushwa", null, RoutingLevel.OVERSIGHT,
                1440, 0, true, true, ReportVisibility.PRIVATE, "shield");
        assignPublicId(c, UUID.randomUUID());
        return c;
    }

    /**
     * Builds a saved-looking report (public id + created instant assigned).
     *
     * @param reporterProfileId reporter id, or {@code null} for anonymous.
     * @param category          its category.
     * @param visibility        its visibility.
     * @return the report.
     */
    public static Report report(UUID reporterProfileId, IssueCategory category, ReportVisibility visibility) {
        Report r = new Report(reporterProfileId, category, "Bomba limepasuka", "Maji yanamwagika",
                null, UUID.randomUUID(), UUID.randomUUID(), null, visibility, ReportPriority.NORMAL,
                Instant.now().plusSeconds(3600));
        r.setCode("TAR-2026-000001");
        assignPublicId(r, UUID.randomUUID());
        return r;
    }

    /** Reflectively assigns {@code BaseEntity.publicId} for tests (production sets it on @PrePersist). */
    public static void assignPublicId(BaseEntity entity, UUID publicId) {
        try {
            Field field = BaseEntity.class.getDeclaredField("publicId");
            field.setAccessible(true);
            field.set(entity, publicId);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to assign test publicId", ex);
        }
    }
}
