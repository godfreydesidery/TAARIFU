package com.taarifu.media.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body to obtain a pre-signed upload URL for a new attachment (PRD §21 EI-8,
 * ARCHITECTURE.md §5).
 *
 * <p>Responsibility: the validated boundary input describing <i>what</i> the client intends to upload
 * and <i>to which host resource</i> — never the bytes themselves (those go straight to object storage
 * via the returned pre-signed URL). The server generates the storage key; the client never proposes one
 * (path-traversal / collision safety).</p>
 *
 * <p>WHY {@code ownerType}/{@code ownerId} are opaque here: the host resource lives in another module
 * this one must not import (ARCHITECTURE.md §3.2). The host module validates ownership/visibility — at
 * file time through {@code media.api.MediaAttachmentApi#validateAndBind} (the wired pipeline path, e.g.
 * reporting's {@code MediaAttachmentValidator}) and at serve time through its visibility port (e.g.
 * {@code reporting.api.ReportMediaAccessApi}). PHASE-3: an upfront "may this caller attach to this existing
 * host?" check on the bound-at-request path needs a host-published attach-authorization port (the seam
 * {@code MediaService.requestUpload} is ready to call); this DTO only carries the reference.</p>
 *
 * @param ownerType        host-resource discriminator (e.g. {@code REPORT}, {@code PROFILE_AVATAR}).
 * @param ownerId          host resource public id the attachment will belong to.
 * @param originalFilename client-supplied display filename (retained for display only).
 * @param contentType      declared MIME type the signed PUT will bind.
 * @param sizeBytes        declared object size in bytes (used for quota/max-size enforcement).
 */
public record UploadRequest(
        @NotBlank @Size(max = 48) String ownerType,
        @NotNull UUID ownerId,
        @Size(max = 255) String originalFilename,
        @NotBlank @Size(max = 128) String contentType,
        @NotNull @Positive Long sizeBytes
) {
}
