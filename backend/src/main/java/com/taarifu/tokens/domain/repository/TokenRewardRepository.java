package com.taarifu.tokens.domain.repository;

import com.taarifu.tokens.domain.model.TokenReward;
import com.taarifu.tokens.domain.model.enums.RewardBehaviour;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link TokenReward} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: resolves the active reward config for a behaviour (used by the earn path) and supports
 * admin CRUD listing. Soft-deleted rows are excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface TokenRewardRepository extends JpaRepository<TokenReward, Long> {

    /**
     * @param behaviour the civic behaviour.
     * @return the active reward config for the behaviour, or empty if none configured.
     */
    Optional<TokenReward> findByBehaviourAndActiveTrue(RewardBehaviour behaviour);

    /** @return all active reward configs (admin listing). */
    List<TokenReward> findByActiveTrue();

    /**
     * @param publicId a reward's public id.
     * @return the reward, or empty.
     */
    Optional<TokenReward> findByPublicId(UUID publicId);
}
