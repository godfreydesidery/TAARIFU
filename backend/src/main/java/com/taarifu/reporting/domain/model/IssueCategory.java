package com.taarifu.reporting.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.model.enums.RoutingLevel;
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

/**
 * A node in the hierarchical citizen-reportable issue taxonomy (PRD §9.1, Appendix D; UC-B14).
 *
 * <p>Responsibility: classifies reports and carries the <b>defaults</b> that drive routing, SLA, privacy,
 * and anonymity for reports filed under it: the {@link #defaultRoutingLevel} (a routing token, not an
 * agency — Appendix D.1), the default SLA pair ({@link #defaultSlaTtfrMinutes}/{@link
 * #defaultSlaTtrMinutes}), the {@link #sensitive} flag (anonymous-eligibility per D-Q1), the
 * {@link #forcePrivate} flag (GBV/Corruption are forced PRIVATE — Appendix D.4), and the
 * {@link #defaultVisibility}.</p>
 *
 * <p>Hierarchy: a category has an optional {@link #parent} (self-FK, same single-table pattern as
 * {@code geography.Location}), so the taxonomy is a tree (e.g. <i>Health → individual-complaint</i>). A
 * report files against any node, leaf or branch. Defaults are read from the chosen node; sub-cases that
 * tighten the profile (e.g. a sensitive "misconduct" sub-category under a non-sensitive parent) set their
 * own flags on the child row (most-specific wins, configured by Admin per UC-B14/B15).</p>
 *
 * <p>WHY SLA is stored as <b>minutes</b> (not a duration string): an integer is trivially comparable for
 * "tightest SLA in the tree", computable into {@code dueAt = now + sla}, and validate-friendly against
 * the DB ({@code ddl-auto=validate}); the human "48h / 14d" display is a presentation concern.</p>
 *
 * <p>WHY these are <i>defaults</i>, not the live rule: all rows are admin-configurable (UC-B14) and the
 * routing engine may override per area (UC-B15, Appendix D.3). This entity is the seed/default carrier;
 * the effective-dated per-area routing/SLA matrix is a later (responders) increment.</p>
 */
@Entity
@Table(name = "issue_category", indexes = {
        @Index(name = "ix_issue_category_code", columnList = "code", unique = true),
        @Index(name = "ix_issue_category_parent", columnList = "parent_id"),
        @Index(name = "ix_issue_category_active", columnList = "active")
})
@SQLRestriction("deleted = false")
public class IssueCategory extends BaseEntity {

    /** Stable machine code for the category (e.g. {@code WATER_SANITATION}); unique; import/match key. */
    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    /** Display name in the civic vocabulary (Swahili-first, e.g. "Maji na Usafi wa Mazingira"). */
    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /**
     * Optional parent category (self-FK). {@code null} for a top-level category. A <b>real FK</b> to the
     * same table, never a loose id (fixes legacy, PRD §6.3).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private IssueCategory parent;

    /** Default routing token reports under this category resolve to (Appendix D.1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_routing_level", nullable = false, length = 24)
    private RoutingLevel defaultRoutingLevel;

    /**
     * Default time-to-first-response in <b>minutes</b> (TTFR — submission to first official action).
     * Used to compute the citizen-facing expected first response at file time (UC-D01 step 7).
     */
    @Column(name = "default_sla_ttfr_minutes", nullable = false)
    private int defaultSlaTtfrMinutes;

    /**
     * Default time-to-resolution in <b>minutes</b> (TTR — submission to RESOLVED). Drives
     * {@code Report.dueAt = filedAt + ttr} (UC-D04: "Set dueAt = now + category.SLA").
     */
    @Column(name = "default_sla_ttr_minutes", nullable = false)
    private int defaultSlaTtrMinutes;

    /**
     * {@code true} if reports here are anonymity-eligible and require only T1 (D-Q1, Appendix D.4):
     * GBV and Corruption are always sensitive; partial sub-cases (negligence/misconduct/abuse/dispute)
     * set this on their child row. Sensitive categories also carry stricter moderation/anti-abuse
     * downstream (DEFERRED to moderation).
     */
    @Column(name = "sensitive", nullable = false)
    private boolean sensitive = false;

    /**
     * {@code true} if reports here are <b>forced</b> to {@link ReportVisibility#PRIVATE} regardless of the
     * citizen's choice (GBV/Corruption — Appendix D.4 "never PUBLIC"). Distinct from {@link #sensitive}: a
     * category can be sensitive (anonymity-eligible) without forcing private, and vice versa, though the
     * always-sensitive ones force private too.
     */
    @Column(name = "force_private", nullable = false)
    private boolean forcePrivate = false;

    /** The default visibility applied when the citizen does not choose (and {@code forcePrivate} is false). */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_visibility", nullable = false, length = 16)
    private ReportVisibility defaultVisibility = ReportVisibility.PUBLIC;

    /** Optional UI icon token (e.g. {@code "water-drop"}) for category pickers; presentation only. */
    @Column(name = "icon", length = 64)
    private String icon;

    /** Civic on/off switch: a retired category is hidden from the picker but kept for historical reports. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** JPA requires a no-arg constructor; application code uses the all-args factory below. */
    protected IssueCategory() {
    }

    /**
     * Creates a category. Used by the application service and seed/import paths.
     *
     * @param code                  stable machine code (unique).
     * @param name                  Swahili-first display name.
     * @param parent                optional parent node, or {@code null} for top-level.
     * @param defaultRoutingLevel   default routing token.
     * @param defaultSlaTtfrMinutes default TTFR (minutes).
     * @param defaultSlaTtrMinutes  default TTR (minutes).
     * @param sensitive             anonymity-eligible (D-Q1).
     * @param forcePrivate          force PRIVATE regardless of citizen choice (GBV/Corruption).
     * @param defaultVisibility     default visibility when not forced/chosen.
     * @param icon                  optional UI icon token.
     */
    public IssueCategory(String code, String name, IssueCategory parent, RoutingLevel defaultRoutingLevel,
                         int defaultSlaTtfrMinutes, int defaultSlaTtrMinutes, boolean sensitive,
                         boolean forcePrivate, ReportVisibility defaultVisibility, String icon) {
        this.code = code;
        this.name = name;
        this.parent = parent;
        this.defaultRoutingLevel = defaultRoutingLevel;
        this.defaultSlaTtfrMinutes = defaultSlaTtfrMinutes;
        this.defaultSlaTtrMinutes = defaultSlaTtrMinutes;
        this.sensitive = sensitive;
        this.forcePrivate = forcePrivate;
        this.defaultVisibility = defaultVisibility;
        this.icon = icon;
    }

    /**
     * Applies an admin edit to the mutable attributes of this category (UC-B14). The {@link #code} is
     * immutable once issued (clients/imports match on it).
     *
     * @param name                  new display name.
     * @param defaultRoutingLevel   new routing token.
     * @param defaultSlaTtfrMinutes new TTFR (minutes).
     * @param defaultSlaTtrMinutes  new TTR (minutes).
     * @param sensitive             new sensitivity flag.
     * @param forcePrivate          new force-private flag.
     * @param defaultVisibility     new default visibility.
     * @param icon                  new UI icon token.
     * @param active                new active flag.
     */
    public void applyEdit(String name, RoutingLevel defaultRoutingLevel, int defaultSlaTtfrMinutes,
                          int defaultSlaTtrMinutes, boolean sensitive, boolean forcePrivate,
                          ReportVisibility defaultVisibility, String icon, boolean active) {
        this.name = name;
        this.defaultRoutingLevel = defaultRoutingLevel;
        this.defaultSlaTtfrMinutes = defaultSlaTtfrMinutes;
        this.defaultSlaTtrMinutes = defaultSlaTtrMinutes;
        this.sensitive = sensitive;
        this.forcePrivate = forcePrivate;
        this.defaultVisibility = defaultVisibility;
        this.icon = icon;
        this.active = active;
    }

    /** @return the stable machine code. */
    public String getCode() {
        return code;
    }

    /** @return the display name. */
    public String getName() {
        return name;
    }

    /** @return the parent category, or {@code null} for a top-level node. */
    public IssueCategory getParent() {
        return parent;
    }

    /** @return the default routing token (Appendix D.1). */
    public RoutingLevel getDefaultRoutingLevel() {
        return defaultRoutingLevel;
    }

    /** @return the default TTFR in minutes. */
    public int getDefaultSlaTtfrMinutes() {
        return defaultSlaTtfrMinutes;
    }

    /** @return the default TTR in minutes (drives {@code Report.dueAt}). */
    public int getDefaultSlaTtrMinutes() {
        return defaultSlaTtrMinutes;
    }

    /** @return {@code true} if reports here are anonymity-eligible (T1 sufficient) per D-Q1. */
    public boolean isSensitive() {
        return sensitive;
    }

    /** @return {@code true} if reports here are forced PRIVATE regardless of citizen choice. */
    public boolean isForcePrivate() {
        return forcePrivate;
    }

    /** @return the default visibility applied when not forced/chosen. */
    public ReportVisibility getDefaultVisibility() {
        return defaultVisibility;
    }

    /** @return the optional UI icon token, or {@code null}. */
    public String getIcon() {
        return icon;
    }

    /** @return {@code true} if the category is active (shown in the picker). */
    public boolean isActive() {
        return active;
    }
}
