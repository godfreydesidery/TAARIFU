package com.taarifu.admin.domain.repository;

import com.taarifu.admin.domain.model.AppConfig;
import com.taarifu.admin.domain.model.enums.ClientPlatform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link AppConfig} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: load/upsert the at-most-one live config row per {@link ClientPlatform}. Soft-deleted
 * rows are excluded by the entity's {@code @SQLRestriction}. Public lookup by {@code publicId} (ADR-0006).</p>
 */
public interface AppConfigRepository extends JpaRepository<AppConfig, Long> {

    /**
     * @param platform the client platform.
     * @return the live config for that platform, or empty if none is configured yet.
     */
    Optional<AppConfig> findByPlatform(ClientPlatform platform);

    /**
     * @param publicId the config row's public id.
     * @return the config, or empty.
     */
    Optional<AppConfig> findByPublicId(UUID publicId);
}
