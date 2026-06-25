package com.taarifu.media.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Media's minimised slice of a data-subject ACCESS export — the attachments the subject uploaded (PRD §18
 * PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: the boundary shape {@code MediaExportContributor} returns for the privacy module's
 * export aggregation — the subject's <b>own</b> uploaded objects, returned to the subject.</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18):</b> lists only object metadata (public id, original filename,
 * content type, size, scan state) — <b>never the bytes</b> and never the raw storage {@code objectKey} (an
 * internal store reference, not the subject's data; serving bytes goes through the access-controlled download
 * path, never an export). The subject is identified by their authenticated account; this DTO carries no
 * national/voter ID.</p>
 *
 * @param objects the objects the subject uploaded (may be empty).
 */
public record MediaExportView(List<UploadedObject> objects) {

    /**
     * One object the subject uploaded, minimised to display metadata only.
     *
     * @param mediaPublicId    the object's public id.
     * @param originalFilename the client-supplied display filename, or {@code null}.
     * @param contentType      the declared MIME type, or {@code null}.
     * @param sizeBytes        the declared size in bytes, or {@code null}.
     * @param scanStatus       the malware-scan state name.
     * @param uploadedAt       when the object record was created (UTC).
     */
    public record UploadedObject(
            UUID mediaPublicId,
            String originalFilename,
            String contentType,
            Long sizeBytes,
            String scanStatus,
            Instant uploadedAt) {
    }
}
