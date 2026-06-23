package com.taarifu.institutions.domain.repository;

import com.taarifu.institutions.domain.model.ParliamentRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link ParliamentRole} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: the persistence port for the parliament-role catalogue and admin CRUD. Public
 * lookups are by {@code publicId}; {@code code} supports idempotent admin upserts. Soft-deleted rows are
 * excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface ParliamentRoleRepository extends JpaRepository<ParliamentRole, Long> {

    /**
     * @param publicId the role's public id.
     * @return the matching role, or empty.
     */
    Optional<ParliamentRole> findByPublicId(UUID publicId);

    /**
     * @param code the stable role code.
     * @return the matching role, or empty.
     */
    Optional<ParliamentRole> findByCode(String code);
}
