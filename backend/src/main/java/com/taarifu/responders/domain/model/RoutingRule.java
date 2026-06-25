package com.taarifu.responders.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.responders.domain.model.enums.ProviderSelectionMode;
import com.taarifu.responders.domain.model.enums.ResponderType;
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

import java.util.UUID;

/**
 * A rule that maps a report category (and optional sub-category) to the responder kind/sector that
 * handles it, then narrows by area or defers to a citizen-selected provider (PRD §24.2, §24.5).
 *
 * <p>Responsibility: encodes one row of the routing table — "{@code (category[, sub-category])} →
 * {@link ResponderType}/sector, resolved by {@link ProviderSelectionMode} (auto-by-area vs.
 * citizen-selected), with a fallback ladder" (§24.2). Government-area categories continue to route by
 * admin level; if no provider matches, routing falls back through the §25.2 ladder. The actual
 * resolution algorithm lives in the reporting module (which owns the {@code Report}); this module owns
 * the <b>rule data</b> and a directory of who-handles-what.</p>
 *
 * <p>WHY category/sub-category are id references (not FKs): {@code IssueCategory} is owned by the
 * {@code reporting} module, a peer feature module not importable here (ARCHITECTURE.md §3.2). The rule
 * references categories by their public id; existence is validated synchronously against reporting's
 * published {@link com.taarifu.reporting.api.IssueCategoryQueryApi} on the create path
 * ({@code ResponderAdminService.createRoutingRule}, sync {@code responders → reporting} — ADR-0013 §4a),
 * and the routing handler resolves the report's category against the rule table at routing time.</p>
 *
 * <p>WHY an optional {@code preferredResponderId}: §24.2's "electricity → TANESCO" style rules can pin
 * a default provider directly; when {@link ProviderSelectionMode#CITIZEN_SELECTED} the field is left
 * null and the report-submission flow presents a provider picker instead.</p>
 */
@Entity
@Table(name = "routing_rule", indexes = {
        @Index(name = "ix_routing_rule_category", columnList = "category_public_id"),
        @Index(name = "ix_routing_rule_responder_type", columnList = "responder_type"),
        @Index(name = "ix_routing_rule_active", columnList = "active")
})
@SQLRestriction("deleted = false")
public class RoutingRule extends BaseEntity {

    /**
     * The reporting-module category this rule routes (referenced by id, no FK). Required — every rule
     * is keyed by at least a top-level category. Existence is validated against reporting's published
     * {@code IssueCategoryQueryApi} on the create path (ADR-0013 §4a).
     */
    @Column(name = "category_public_id", nullable = false)
    private UUID categoryPublicId;

    /**
     * The optional sub-category narrowing this rule (referenced by id). {@code null} means the rule
     * applies to the whole category. A more specific (sub-category) rule takes precedence at routing.
     */
    @Column(name = "sub_category_public_id")
    private UUID subCategoryPublicId;

    /** The responder kind/sector this category routes to (PRD §24.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "responder_type", nullable = false, length = 32)
    private ResponderType responderType;

    /** Whether the platform auto-resolves by area or the citizen selects the provider (PRD §24.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "selection_mode", nullable = false, length = 24)
    private ProviderSelectionMode selectionMode = ProviderSelectionMode.AUTO_BY_AREA;

    /**
     * An optional pinned default responder for this rule (e.g. a single nationwide agency), referenced
     * by id. {@code null} for {@link ProviderSelectionMode#CITIZEN_SELECTED} rules and for purely
     * area-resolved rules.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preferred_responder_id")
    private Responder preferredResponder;

    /**
     * Ordering for deterministic rule evaluation (lower = earlier). Lets a specific rule win over a
     * general one and gives the §25.2 fallback ladder a stable order. Defaults to 100.
     */
    @Column(name = "priority", nullable = false)
    private int priority = 100;

    /** Whether this rule participates in routing. Inactive rules are retained for history/audit. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** JPA requires a no-arg constructor; not for application use. */
    protected RoutingRule() {
    }

    /**
     * Creates a routing rule for a category.
     *
     * @param categoryPublicId the reporting category id this rule routes.
     * @param responderType    the responder kind/sector to route to.
     * @param selectionMode    auto-by-area vs. citizen-selected.
     * @return a transient, active rule ready to persist.
     */
    public static RoutingRule create(UUID categoryPublicId, ResponderType responderType,
                                     ProviderSelectionMode selectionMode) {
        RoutingRule rule = new RoutingRule();
        rule.categoryPublicId = categoryPublicId;
        rule.responderType = responderType;
        rule.selectionMode = selectionMode;
        rule.active = true;
        return rule;
    }

    /** Narrows the rule to a sub-category (referenced by id). */
    public void setSubCategoryPublicId(UUID subCategoryPublicId) {
        this.subCategoryPublicId = subCategoryPublicId;
    }

    /** Pins a default responder for this rule. */
    public void setPreferredResponder(Responder preferredResponder) {
        this.preferredResponder = preferredResponder;
    }

    /** Sets the evaluation priority (lower wins). */
    public void setPriority(int priority) {
        this.priority = priority;
    }

    /** Sets the selection mode (auto-by-area vs. citizen-selected). */
    public void setSelectionMode(ProviderSelectionMode selectionMode) {
        this.selectionMode = selectionMode;
    }

    /** Sets the responder kind/sector. */
    public void setResponderType(ResponderType responderType) {
        this.responderType = responderType;
    }

    /** Activates or deactivates the rule. */
    public void setActive(boolean active) {
        this.active = active;
    }

    /** @return the routed category id. */
    public UUID getCategoryPublicId() {
        return categoryPublicId;
    }

    /** @return the sub-category id, or {@code null}. */
    public UUID getSubCategoryPublicId() {
        return subCategoryPublicId;
    }

    /** @return the responder kind/sector. */
    public ResponderType getResponderType() {
        return responderType;
    }

    /** @return the selection mode. */
    public ProviderSelectionMode getSelectionMode() {
        return selectionMode;
    }

    /** @return the pinned default responder, or {@code null}. */
    public Responder getPreferredResponder() {
        return preferredResponder;
    }

    /** @return the evaluation priority. */
    public int getPriority() {
        return priority;
    }

    /** @return whether the rule is active. */
    public boolean isActive() {
        return active;
    }
}
