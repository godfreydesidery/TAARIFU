package com.taarifu.identity.domain.repository;

import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link RoleAssignment} (ARCHITECTURE.md §3.3, §6.2).
 *
 * <p>Responsibility: loads a user's role grants (with their scope) for authorization. The scope-checker
 * (auth increment) reads these to enforce area/category/constituency limits. Public lookup by
 * {@code publicId} (ADR-0006).</p>
 */
public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, Long> {

    /**
     * @param user the account.
     * @return all role grants held by the account (additive roles — §6.4).
     */
    List<RoleAssignment> findByUser(User user);

    /**
     * @param userPublicId the account's public id.
     * @return all role grants held by that account (additive roles — §6.4).
     */
    List<RoleAssignment> findByUser_PublicId(UUID userPublicId);

    /**
     * @param userPublicId the account's public id.
     * @param status       the grant status to filter by (typically {@link RoleStatus#ACTIVE}).
     * @return matching grants — the live scope source for the {@code ScopeGuard} (MF-3).
     */
    List<RoleAssignment> findByUser_PublicIdAndStatus(UUID userPublicId, RoleStatus status);

    /**
     * @param publicId the assignment's public id.
     * @return the assignment, or empty.
     */
    Optional<RoleAssignment> findByPublicId(UUID publicId);
}
