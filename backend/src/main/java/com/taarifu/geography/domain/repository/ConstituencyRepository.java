package com.taarifu.geography.domain.repository;

import com.taarifu.geography.domain.model.Constituency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Constituency} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: public lookup of constituencies by {@code publicId} for the "constituency +
 * current wards" read endpoint. Soft-deleted rows are excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface ConstituencyRepository extends JpaRepository<Constituency, Long> {

    /**
     * @param publicId the constituency's public id.
     * @return the matching constituency, or empty if none/soft-deleted.
     */
    Optional<Constituency> findByPublicId(UUID publicId);
}
