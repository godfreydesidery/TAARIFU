package com.taarifu.tokens.domain.repository;

import com.taarifu.tokens.domain.model.ActionCostPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link ActionCostPolicy} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: resolves the active cost/quota policy for a metered action, preferring a role-specific
 * row and falling back to the action's default ({@code roleName IS NULL}) row. Admin CRUD lists/edits use
 * the remaining finders. Soft-deleted rows are excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface ActionCostPolicyRepository extends JpaRepository<ActionCostPolicy, Long> {

    /**
     * Finds the active policy for an action and a specific role.
     *
     * @param actionCode the metered action.
     * @param roleName   the role.
     * @return the active role-specific policy, or empty.
     */
    Optional<ActionCostPolicy> findByActionCodeAndRoleNameAndActiveTrue(String actionCode, String roleName);

    /**
     * Finds the active <b>default</b> policy for an action (the {@code roleName IS NULL} fallback).
     *
     * @param actionCode the metered action.
     * @return the active default policy, or empty.
     */
    @Query("""
            select p from ActionCostPolicy p
            where p.actionCode = :actionCode and p.roleName is null and p.active = true
            """)
    Optional<ActionCostPolicy> findActiveDefault(@Param("actionCode") String actionCode);

    /**
     * @param actionCode the metered action.
     * @return all active policies (default + per-role) for the action.
     */
    List<ActionCostPolicy> findByActionCodeAndActiveTrue(String actionCode);

    /** @return all active policies (admin listing). */
    List<ActionCostPolicy> findByActiveTrue();

    /**
     * @param publicId a policy's public id.
     * @return the policy, or empty.
     */
    Optional<ActionCostPolicy> findByPublicId(UUID publicId);
}
