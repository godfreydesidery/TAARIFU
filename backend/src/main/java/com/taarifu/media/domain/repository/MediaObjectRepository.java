package com.taarifu.media.domain.repository;

import com.taarifu.media.domain.model.MediaObject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link MediaObject} records (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: the persistence port for the media slice. Public lookups are by {@code publicId}
 * (never the internal {@code Long id}, ADR-0006); the scan callback resolves an object by its storage
 * {@code objectKey}. Soft-deleted rows are excluded automatically by the entity's {@code @SQLRestriction}
 * (ARCHITECTURE.md §4.2).</p>
 */
public interface MediaObjectRepository extends JpaRepository<MediaObject, Long> {

    /**
     * @param publicId the media object's public id (used in download URLs and callbacks).
     * @return the matching object, or empty if none/soft-deleted.
     */
    Optional<MediaObject> findByPublicId(UUID publicId);

    /**
     * Looks up an object by its storage key — the identity the {@code ObjectStore} and scan callback use.
     *
     * @param objectKey the unique storage key.
     * @return the matching object, or empty if none/soft-deleted.
     */
    Optional<MediaObject> findByObjectKey(String objectKey);
}
