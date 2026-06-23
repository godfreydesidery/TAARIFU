package com.taarifu.identity.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.identity.domain.model.enums.RoleStatus;
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

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Grants a {@link Role} to a {@link User} with optional <b>attribute scope</b> (PRD §7.1, §9.1, D20;
 * ARCHITECTURE.md §6.2).
 *
 * <p>Responsibility: the join that makes RBAC <b>scoped</b> — e.g. "this responder agent only within
 * these wards and these issue categories", "this representative only their own constituency". A custom
 * scope-checker (auth increment) reads {@link #areaIds}/{@link #categoryIds}/{@link #constituency} to
 * enforce "act only within assigned scope". Roles are additive on one account, so a user may hold many
 * assignments (§6.4).</p>
 *
 * <p>WHY {@code areaIds}/{@code categoryIds} are stored as collections of <b>public UUIDs</b> rather
 * than FK join tables to {@code Location}/{@code IssueCategory}: a single assignment may scope to an
 * arbitrary set spanning levels and (for categories) a module not yet built (reporting). Storing the
 * referenced entities' public ids in an element-collection side table keeps the assignment self-
 * contained and avoids a cross-module FK explosion, while remaining resolvable via each module's public
 * API. The single-valued {@link #constituency} is a genuine FK because it is one electoral unit and the
 * geography module exists. (This is the documented, deliberate exception to "always a real FK" for the
 * multi-valued, cross-module scope sets — ARCHITECTURE.md §4.3 spirit, KISS.)</p>
 */
@Entity
@Table(name = "role_assignment", indexes = {
        @Index(name = "ix_role_assignment_user", columnList = "user_id"),
        @Index(name = "ix_role_assignment_role", columnList = "role_id"),
        @Index(name = "ix_role_assignment_status", columnList = "status")
})
@SQLRestriction("deleted = false")
public class RoleAssignment extends BaseEntity {

    /** The account holding this role grant (FK). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The granted role (FK to the catalogue). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    /** Lifecycle of this specific grant (pending verification, active, suspended, former). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RoleStatus status = RoleStatus.PENDING_VERIFICATION;

    /**
     * Area scope: public ids of {@code Location}s this grant is limited to (any level). Empty = no area
     * restriction. Stored in a side table {@code role_assignment_area}.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "role_assignment_area",
            joinColumns = @JoinColumn(name = "role_assignment_id"))
    @Column(name = "area_public_id", nullable = false)
    private Set<UUID> areaIds = new HashSet<>();

    /**
     * Category scope: public ids of issue categories this grant is limited to (resolved via the
     * reporting module's public API when it exists). Empty = no category restriction. Stored in
     * {@code role_assignment_category}.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "role_assignment_category",
            joinColumns = @JoinColumn(name = "role_assignment_id"))
    @Column(name = "category_public_id", nullable = false)
    private Set<UUID> categoryIds = new HashSet<>();

    /**
     * Single constituency scope (FK) — e.g. a representative limited to their own constituency. A real
     * FK because it is one electoral unit and geography exists; {@code null} = not constituency-scoped.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "constituency_id")
    private Constituency constituency;

    /** When this grant takes effect (UTC); {@code null} = effective on creation. */
    @Column(name = "effective_from")
    private Instant effectiveFrom;

    /** When this grant ends (UTC); {@code null} = open-ended. */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** JPA requires a no-arg constructor; not for application use. */
    protected RoleAssignment() {
    }

    /** @return the account holding the grant. */
    public User getUser() {
        return user;
    }

    /** @return the granted role. */
    public Role getRole() {
        return role;
    }

    /** @return the grant lifecycle status. */
    public RoleStatus getStatus() {
        return status;
    }

    /** @return the area-scope public ids (unmodifiable view); empty = unrestricted. */
    public Set<UUID> getAreaIds() {
        return Set.copyOf(areaIds);
    }

    /** @return the category-scope public ids (unmodifiable view); empty = unrestricted. */
    public Set<UUID> getCategoryIds() {
        return Set.copyOf(categoryIds);
    }

    /** @return the single constituency scope, or {@code null}. */
    public Constituency getConstituency() {
        return constituency;
    }

    /** @return the grant start instant, or {@code null}. */
    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    /** @return the grant end instant, or {@code null}. */
    public Instant getEffectiveTo() {
        return effectiveTo;
    }
}
