package com.taarifu.admin.domain.repository;

import com.taarifu.admin.domain.model.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link FeatureFlag} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: list/look-up feature flags for the admin console and the public config read. The
 * unique {@code flag_key} is the natural key for upserts; soft-deleted flags are hidden by the entity's
 * {@code @SQLRestriction}.</p>
 */
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {

    /**
     * @param key the stable machine flag key.
     * @return the flag, or empty.
     */
    Optional<FeatureFlag> findByKey(String key);

    /**
     * @param publicId the flag's public id.
     * @return the flag, or empty.
     */
    Optional<FeatureFlag> findByPublicId(UUID publicId);

    /**
     * @return all flags ordered by key (a stable, small reference set — the full list is returned, not
     *         paginated, because the toggle set is operator-curated and bounded).
     */
    List<FeatureFlag> findAllByOrderByKeyAsc();
}
