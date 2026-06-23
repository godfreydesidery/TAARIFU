package com.taarifu.identity.domain.repository;

import com.taarifu.identity.domain.model.Role;
import com.taarifu.identity.domain.model.enums.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for the {@link Role} catalogue (ARCHITECTURE.md §3.3, PRD §7.2).
 *
 * <p>Responsibility: resolves catalogue rows by {@link RoleName} when granting roles. The catalogue is
 * seeded in migration {@code V102} so {@code findByName} returns the canonical row.</p>
 */
public interface RoleRepository extends JpaRepository<Role, Long> {

    /**
     * @param name the canonical role name.
     * @return the catalogue row, or empty if not seeded.
     */
    Optional<Role> findByName(RoleName name);
}
