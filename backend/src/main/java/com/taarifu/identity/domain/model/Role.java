package com.taarifu.identity.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.identity.domain.model.enums.RoleName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * The role catalogue entry — a grantable role (PRD §7.2, §9.1; ARCHITECTURE.md §6.2).
 *
 * <p>Responsibility: the reference row for each {@link RoleName}. {@link RoleAssignment} links a
 * {@link User} to a {@code Role} with optional <b>scope</b> (areas/categories/constituency). Roles are
 * additive on one account (§6.4).</p>
 *
 * <p>WHY a table (not just the enum): roles carry descriptive metadata and are referenced by FK from
 * assignments, and seeding them in the DB (V102) keeps the catalogue auditable and admin-manageable
 * (PRD §6, §19) while the {@link RoleName} enum keeps the set type-safe in code.</p>
 */
@Entity
@Table(name = "role", indexes = {
        @Index(name = "ux_role_name", columnList = "name", unique = true)
})
@SQLRestriction("deleted = false")
public class Role extends BaseEntity {

    /** The canonical role identifier; unique. */
    @Enumerated(EnumType.STRING)
    @Column(name = "name", nullable = false, unique = true, length = 32)
    private RoleName name;

    /** Human-readable description of the role's purpose (admin/UI display). */
    @Column(name = "description", length = 255)
    private String description;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Role() {
    }

    /** @return the canonical role name. */
    public RoleName getName() {
        return name;
    }

    /** @return the role description. */
    public String getDescription() {
        return description;
    }
}
