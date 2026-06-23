package com.taarifu.media.api.dto;

import java.util.UUID;

/**
 * Response carrying a short-lived pre-signed download URL for a scanned-CLEAN media object
 * (PRD §21 EI-8, ARCHITECTURE.md §5).
 *
 * <p>Responsibility: returns the GET URL the client uses to fetch bytes directly from object storage,
 * plus the metadata it needs to render the attachment ({@code contentType}, {@code originalFilename}).
 * A URL is <b>only ever issued for a {@code CLEAN} object</b> — the service refuses PENDING/INFECTED/
 * FAILED with a conflict, so this DTO never represents unsafe or unscanned media (EI-8 serving rule).</p>
 *
 * @param mediaId          the media object's public id.
 * @param downloadUrl      the absolute, short-lived pre-signed GET URL.
 * @param contentType      the object's MIME type (for the client to set when rendering).
 * @param originalFilename the display filename, or {@code null}.
 * @param expiresInSeconds the download URL's remaining validity, in seconds.
 */
public record DownloadUrlDto(
        UUID mediaId,
        String downloadUrl,
        String contentType,
        String originalFilename,
        long expiresInSeconds
) {
}
