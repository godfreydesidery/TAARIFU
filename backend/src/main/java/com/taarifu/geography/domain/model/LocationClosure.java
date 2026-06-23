package com.taarifu.geography.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A row of the administrative-hierarchy <b>closure table</b> (ARCHITECTURE.md §4.3, PRD §9.0).
 *
 * <p>Responsibility: stores one entry per (ancestor, descendant) pair — including the self-pair at
 * {@code depth = 0} — so "all descendants of a District" or "the full ancestor chain of a Ward" is a
 * single indexed query rather than a recursive walk. This is what makes national-scale hierarchy
 * reads fast (PRD §15).</p>
 *
 * <p>WHY a closure table over materialised paths or recursive CTEs: closure tables give O(1) indexed
 * lookups for both directions (ancestors and descendants) and tolerate re-parenting via row rewrites,
 * which suits re-delimitation (EI-14). {@code depth} enables "children only" (depth = 1) vs "all
 * descendants" (depth ≥ 1) queries.</p>
 *
 * <p>Maintenance of closure rows on insert/move is owned by the geography write/seed path (a later
 * increment / the Flyway seed); this read slice only queries them.</p>
 */
@Entity
@Table(name = "location_closure",
        uniqueConstraints = @UniqueConstraint(name = "uq_closure_pair",
                columnNames = {"ancestor_id", "descendant_id"}),
        indexes = {
                @Index(name = "ix_closure_ancestor", columnList = "ancestor_id"),
                @Index(name = "ix_closure_descendant", columnList = "descendant_id")
        })
public class LocationClosure extends BaseEntity {

    /** The ancestor location (FK to {@code location.id}). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ancestor_id", nullable = false)
    private Location ancestor;

    /** The descendant location (FK to {@code location.id}). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "descendant_id", nullable = false)
    private Location descendant;

    /**
     * Number of edges between {@link #ancestor} and {@link #descendant}: {@code 0} for the self-pair,
     * {@code 1} for a direct child, {@code n} for an n-levels-deep descendant.
     */
    @Column(name = "depth", nullable = false)
    private int depth;

    /** JPA requires a no-arg constructor; not for application use. */
    protected LocationClosure() {
    }

    /** @return the ancestor location. */
    public Location getAncestor() {
        return ancestor;
    }

    /** @return the descendant location. */
    public Location getDescendant() {
        return descendant;
    }

    /** @return the edge distance between ancestor and descendant. */
    public int getDepth() {
        return depth;
    }
}
