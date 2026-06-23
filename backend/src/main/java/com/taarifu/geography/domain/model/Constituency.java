package com.taarifu.geography.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * An electoral constituency (Jimbo) — the electoral geography that overlays the administrative chain
 * (PRD §9.0, §9.1).
 *
 * <p>Responsibility: represents the unit a Member of Parliament (Mbunge) is elected to. A constituency
 * belongs to a {@link Location} of type {@code DISTRICT} (its administrative home), but its member
 * wards are mapped through the <b>effective-dated</b> {@link WardConstituency} bridge — not a direct
 * ward FK — so re-delimitation never corrupts history (PRD §9.0).</p>
 *
 * <p>WHY constituency is modelled separately from the administrative {@code Location}: Tanzania has
 * two overlapping geographies; a citizen pins a place and the system derives <i>both</i> the admin
 * chain and the electoral mapping (PRD §9.0). Conflating them (the legacy ambiguity) breaks "find my
 * MP". A constituency is not an admin level, so it is its own entity.</p>
 */
@Entity
@Table(name = "constituency", indexes = {
        @Index(name = "ix_constituency_code", columnList = "code", unique = true),
        @Index(name = "ix_constituency_district", columnList = "district_id")
})
@SQLRestriction("deleted = false")
public class Constituency extends BaseEntity {

    /** Official electoral code for the constituency; unique; used by seed/import to match rows. */
    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    /** Display name of the constituency (e.g. "Rombo"). */
    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /**
     * The administrative district this constituency is homed in (FK to a {@link Location} of type
     * {@code DISTRICT}). A real FK; nullable only to tolerate seed ordering, but expected to be set.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "district_id")
    private Location district;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Constituency() {
    }

    /** @return the official electoral code. */
    public String getCode() {
        return code;
    }

    /** @return the display name. */
    public String getName() {
        return name;
    }

    /** @return the homing district location, or {@code null} if not yet linked. */
    public Location getDistrict() {
        return district;
    }
}
