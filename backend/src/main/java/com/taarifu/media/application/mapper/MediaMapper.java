package com.taarifu.media.application.mapper;

import com.taarifu.media.api.dto.MediaObjectDto;
import com.taarifu.media.domain.model.MediaObject;
import org.springframework.stereotype.Component;

/**
 * Maps media domain entities to their boundary DTOs (ARCHITECTURE.md §3.3, CLAUDE.md §8).
 *
 * <p>Responsibility: the single place that turns a {@link MediaObject} into a {@link MediaObjectDto},
 * so entities never leak past the module boundary (CLAUDE.md §8) and the internal {@code Long id} and
 * raw {@code objectKey} are never exposed. Hand-written (not MapStruct) because the mapping is tiny and
 * involves a derived {@code servable} flag — KISS.</p>
 */
@Component
public class MediaMapper {

    /**
     * Converts a media object to its DTO.
     *
     * @param entity the media object (must not be {@code null}).
     * @return the boundary DTO with the public id, declared metadata, and scan/strip flags.
     */
    public MediaObjectDto toDto(MediaObject entity) {
        return new MediaObjectDto(
                entity.getPublicId(),
                entity.getOwnerType(),
                entity.getOwnerId(),
                entity.getOriginalFilename(),
                entity.getContentType(),
                entity.getSizeBytes(),
                entity.getScanStatus().name(),
                entity.isExifStripped(),
                entity.isServable());
    }
}
