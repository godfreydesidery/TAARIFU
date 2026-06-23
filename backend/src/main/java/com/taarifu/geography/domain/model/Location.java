package com.taarifu.geography.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.geography.domain.model.enums.LocationStatus;
import com.taarifu.geography.domain.model.enums.LocationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

/**
 * A single node in Tanzania's administrative hierarchy, discriminated by {@link LocationType}
 * (PRD §9.0 D6/D14, ARCHITECTURE.md §4.3).
 *
 * <p>Responsibility: one unified table {@code location} holding every level from {@code REGION (Mkoa)}
 * down to {@code HAMLET (Kitongoji)}. The direct {@code parent} FK models the immediate parent;
 * arbitrary-depth ancestor/descendant queries ("all wards under a district") go through the
 * {@link LocationClosure} closure table for index-friendly performance at national scale.</p>
 *
 * <p>WHY one table + closure (not a table per level, nor recursive self-joins): a per-level mirror
 * was the legacy {@code Area} mistake (duplication, drift); recursive CTEs don't index well for
 * deep "all descendants" reads. The closure table answers those in a single indexed lookup
 * (ARCHITECTURE.md §4.3).</p>
 *
 * <p>Geospatial fields (PostGIS) support EI-7 GPS→ward resolution: {@link #boundary} is the optional
 * ward polygon used for point-in-polygon; {@link #centroid} a representative point for display. Both
 * are nullable — absence degrades to manual ward drill-down (the model never hard-depends on geometry;
 * PRD §9.0, EI-7).</p>
 *
 * <p>Soft-deleted rows are hidden by the {@code @SQLRestriction} so default reads never return
 * tombstones (ARCHITECTURE.md §4.2). Civic retirement (re-delimitation) uses {@link LocationStatus},
 * a separate concept from soft-delete.</p>
 */
@Entity
@Table(name = "location", indexes = {
        @Index(name = "ix_location_parent", columnList = "parent_id"),
        @Index(name = "ix_location_type", columnList = "type"),
        @Index(name = "ix_location_code", columnList = "code", unique = true)
})
@SQLRestriction("deleted = false")
public class Location extends BaseEntity {

    /**
     * Official administrative code for this unit (e.g. a national region/district code).
     * Unique across all locations; used by seed/bulk import to match rows idempotently (EI-14).
     */
    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    /** Display name in the local civic vocabulary (e.g. "Kilimanjaro", "Rombo", "Mengwe"). */
    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /** The level of this node in the hierarchy (Mkoa…Kitongoji). */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private LocationType type;

    /**
     * Immediate parent in the administrative chain (e.g. a Ward's parent Council).
     * {@code null} only for top-level {@link LocationType#REGION} rows. A <b>real FK</b> to the same
     * table (self-reference), never a loose id (fixes legacy, PRD §6.3).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Location parent;

    /** Civic lifecycle status; superseded units become {@link LocationStatus#INACTIVE}, not deleted. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LocationStatus status = LocationStatus.ACTIVE;

    /** Optional latest population figure (reference attribute, PRD §9.1). */
    @Column(name = "population")
    private Long population;

    /**
     * Optional ward boundary polygon (PostGIS, SRID 4326) used for GPS→ward point-in-polygon (EI-7).
     * Typically populated only for {@link LocationType#WARD} rows. Nullable — absence degrades to
     * manual drill-down.
     */
    @Column(name = "boundary", columnDefinition = "geometry(MultiPolygon,4326)")
    private Geometry boundary;

    /** Optional representative centroid point (PostGIS, SRID 4326) for map display/labelling. */
    @Column(name = "centroid", columnDefinition = "geometry(Point,4326)")
    private Point centroid;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Location() {
    }

    /** @return the official administrative code. */
    public String getCode() {
        return code;
    }

    /** @return the display name. */
    public String getName() {
        return name;
    }

    /** @return the hierarchy level. */
    public LocationType getType() {
        return type;
    }

    /** @return the immediate parent, or {@code null} for a region. */
    public Location getParent() {
        return parent;
    }

    /** @return the civic lifecycle status. */
    public LocationStatus getStatus() {
        return status;
    }

    /** @return the optional population figure. */
    public Long getPopulation() {
        return population;
    }

    /** @return the optional boundary polygon, or {@code null}. */
    public Geometry getBoundary() {
        return boundary;
    }

    /** @return the optional centroid point, or {@code null}. */
    public Point getCentroid() {
        return centroid;
    }
}
