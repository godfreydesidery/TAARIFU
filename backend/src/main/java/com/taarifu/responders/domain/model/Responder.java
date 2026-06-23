package com.taarifu.responders.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.responders.domain.model.enums.CoverageType;
import com.taarifu.responders.domain.model.enums.ResponderStatus;
import com.taarifu.responders.domain.model.enums.ResponderType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A <b>responder capability</b> of an {@link Organisation}: which sectors/categories it handles, where
 * it operates, and under what SLA (PRD §24.1, §24.5).
 *
 * <p>Responsibility: the routing target and directory entry. It carries the {@link ResponderType}, the
 * set of <b>handled category ids</b> (referencing the {@code reporting} module's {@code IssueCategory}
 * by id only — see below), its geographic {@code coverage} (a set of area ids, or nationwide), and an
 * SLA policy that may differ from the platform default and may be contractual (§24.1).</p>
 *
 * <p>WHY {@code handledCategoryIds} and {@code coverageAreaIds} are id sets, not FKs: the
 * {@code reporting} module (which owns {@code IssueCategory}) is built in <b>parallel</b> and must not
 * be imported, and even {@code geography} areas are referenced here as opaque ids to keep this module's
 * coupling minimal — routing resolution happens against those ids without a join into another module's
 * tables (ARCHITECTURE.md §3.2). They are stored in side tables via {@code @ElementCollection}.
 * // TODO(wiring): validate/resolve category ids against the reporting module's public API.</p>
 *
 * <p>WHY coverage is a {@link CoverageType} flag plus a set (not just an empty set for nationwide): an
 * empty set is ambiguous; an explicit {@link CoverageType#NATIONWIDE} is an auditable statement that
 * widens who can receive a citizen's report, which matters for PDPA data-minimisation (§24.4).</p>
 */
@Entity
@Table(name = "responder", indexes = {
        @Index(name = "ix_responder_org", columnList = "organisation_id"),
        @Index(name = "ix_responder_type", columnList = "responder_type"),
        @Index(name = "ix_responder_status", columnList = "status")
})
@SQLRestriction("deleted = false")
public class Responder extends BaseEntity {

    /** The owning organisation (FK within this module — both entities live in {@code responders}). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    /** Display name of this capability (e.g. "TANESCO — Kilimanjaro Region"). */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** The functional kind, used as the routing resolution target (PRD §24.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "responder_type", nullable = false, length = 32)
    private ResponderType responderType;

    /** Availability state; only {@link ResponderStatus#ACTIVE} capabilities participate in routing. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ResponderStatus status = ResponderStatus.PENDING;

    /** Whether coverage is an explicit set of areas or the whole country (PRD §24.1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "coverage_type", nullable = false, length = 16)
    private CoverageType coverageType = CoverageType.AREAS;

    /**
     * The reporting-module {@code IssueCategory} public ids this responder handles (PRD §24.1).
     * Referenced by id only (no FK into the parallel reporting module). Stored in
     * {@code responder_category}. // TODO(wiring): validate against reporting's category API.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "responder_category",
            joinColumns = @JoinColumn(name = "responder_id"),
            indexes = @Index(name = "ix_responder_category_cat", columnList = "category_public_id"))
    @Column(name = "category_public_id", nullable = false)
    private Set<UUID> handledCategoryIds = new LinkedHashSet<>();

    /**
     * The geography area public ids this responder covers when {@link #coverageType} is
     * {@link CoverageType#AREAS} (ignored when nationwide). Referenced by id only. Stored in
     * {@code responder_coverage_area}.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "responder_coverage_area",
            joinColumns = @JoinColumn(name = "responder_id"),
            indexes = @Index(name = "ix_responder_coverage_area", columnList = "area_public_id"))
    @Column(name = "area_public_id", nullable = false)
    private Set<UUID> coverageAreaIds = new LinkedHashSet<>();

    /**
     * Free-text SLA policy reference/description for this responder (PRD §24.1 — "may differ from the
     * platform default and may be contractual"). A structured SLA engine lives in reporting (§25.2);
     * this column records the responder's agreed policy. Optional.
     */
    @Column(name = "sla_policy", length = 1000)
    private String slaPolicy;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Responder() {
    }

    /**
     * Creates a new, PENDING responder capability under an organisation.
     *
     * @param organisation the owning organisation.
     * @param name         capability display name.
     * @param responderType the routing target kind.
     * @param coverageType explicit-areas vs. nationwide.
     * @return a transient responder ready to persist.
     */
    public static Responder create(Organisation organisation, String name,
                                   ResponderType responderType, CoverageType coverageType) {
        Responder r = new Responder();
        r.organisation = organisation;
        r.name = name;
        r.responderType = responderType;
        r.coverageType = coverageType;
        r.status = ResponderStatus.PENDING;
        return r;
    }

    /** Replaces the set of handled category ids (defensive copy; null treated as empty). */
    public void setHandledCategoryIds(Set<UUID> categoryIds) {
        this.handledCategoryIds = categoryIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(categoryIds);
    }

    /** Replaces the set of coverage area ids (defensive copy; null treated as empty). */
    public void setCoverageAreaIds(Set<UUID> areaIds) {
        this.coverageAreaIds = areaIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(areaIds);
    }

    /** Updates the SLA policy text. */
    public void setSlaPolicy(String slaPolicy) {
        this.slaPolicy = slaPolicy;
    }

    /** Renames the capability. */
    public void rename(String name) {
        this.name = name;
    }

    /** Changes the routing target kind/sector. */
    public void changeResponderType(ResponderType responderType) {
        this.responderType = responderType;
    }

    /** Changes the coverage mode (areas vs. nationwide). */
    public void changeCoverageType(CoverageType coverageType) {
        this.coverageType = coverageType;
    }

    /** Updates the availability status (activation/suspension/retirement). */
    public void changeStatus(ResponderStatus status) {
        this.status = status;
    }

    /**
     * @param categoryPublicId a reporting-module category id.
     * @return {@code true} if this responder handles that category.
     */
    public boolean handlesCategory(UUID categoryPublicId) {
        return handledCategoryIds.contains(categoryPublicId);
    }

    /**
     * @param areaPublicId a geography area id.
     * @return {@code true} if this responder covers that area — always {@code true} when nationwide,
     *         otherwise membership in {@link #coverageAreaIds}. (Ancestor/descendant area widening is a
     *         routing concern resolved against geography; this is exact membership only.)
     */
    public boolean coversArea(UUID areaPublicId) {
        return coverageType == CoverageType.NATIONWIDE || coverageAreaIds.contains(areaPublicId);
    }

    /** @return the owning organisation. */
    public Organisation getOrganisation() {
        return organisation;
    }

    /** @return the capability display name. */
    public String getName() {
        return name;
    }

    /** @return the routing target kind. */
    public ResponderType getResponderType() {
        return responderType;
    }

    /** @return the availability status. */
    public ResponderStatus getStatus() {
        return status;
    }

    /** @return the coverage mode. */
    public CoverageType getCoverageType() {
        return coverageType;
    }

    /** @return an unmodifiable view of the handled category ids. */
    public Set<UUID> getHandledCategoryIds() {
        return java.util.Collections.unmodifiableSet(handledCategoryIds);
    }

    /** @return an unmodifiable view of the coverage area ids. */
    public Set<UUID> getCoverageAreaIds() {
        return java.util.Collections.unmodifiableSet(coverageAreaIds);
    }

    /** @return the SLA policy text, or {@code null}. */
    public String getSlaPolicy() {
        return slaPolicy;
    }
}
