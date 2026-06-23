package com.taarifu.media.api.dto;

import java.util.UUID;

/**
 * Response DTO describing a media object's metadata and scan state (PRD §21 EI-8,
 * ARCHITECTURE.md §5).
 *
 * <p>Responsibility: the boundary representation of a {@link com.taarifu.media.domain.model.MediaObject}
 * — exposes only the {@code publicId} (never the internal {@code Long id} or the raw {@code objectKey},
 * which is an internal storage detail), the declared metadata, the owner reference, and the
 * {@code scanStatus}/{@code exifStripped} flags a host module needs to decide whether and how to render
 * the attachment. Returned by the scan callback so the caller can observe the resulting state.</p>
 *
 * <p>WHY {@code objectKey} is omitted: it is an internal storage address; clients fetch bytes only via a
 * pre-signed URL, never by key. Leaking keys would enable enumeration/guessing against the store.</p>
 *
 * @param mediaId          the media object's public id.
 * @param ownerType        host-resource discriminator.
 * @param ownerId          host resource public id.
 * @param originalFilename display filename, or {@code null}.
 * @param contentType      MIME type, or {@code null}.
 * @param sizeBytes        size in bytes, or {@code null}.
 * @param scanStatus       the malware-scan state name (PENDING/CLEAN/INFECTED/FAILED).
 * @param exifStripped     whether the EXIF/geo-stripping privacy pass has run.
 * @param servable         convenience flag — {@code true} only when the object may be served (CLEAN).
 */
public record MediaObjectDto(
        UUID mediaId,
        String ownerType,
        UUID ownerId,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        String scanStatus,
        boolean exifStripped,
        boolean servable
) {
}
