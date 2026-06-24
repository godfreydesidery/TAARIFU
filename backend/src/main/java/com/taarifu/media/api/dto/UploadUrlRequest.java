package com.taarifu.media.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/media/upload-url} — obtain a pre-signed PUT for a new attachment in
 * the citizen evidence-attachment pipeline (PRD §21 EI-8, §15, §18; ARCHITECTURE.md §5).
 *
 * <p>Responsibility: the validated boundary input describing <i>what</i> the client intends to upload —
 * never the bytes (those go straight to object storage via the returned pre-signed URL). The server
 * generates the storage key; the client never proposes one (path-traversal / collision safety).</p>
 *
 * <p>WHY there is no {@code ownerId} here (unlike {@link UploadRequest}): in this pipeline the photo is
 * uploaded <b>before</b> the host report exists, so the host id is unknown at upload time. The object is
 * created UNBOUND and bound to the report at file time by the reporting module through the {@code media.api}
 * port. {@code ownerType} is still required so the policy/host catalogue is known up front (currently
 * {@code REPORT}).</p>
 *
 * @param ownerType        host-resource discriminator (e.g. {@code REPORT}); required.
 * @param originalFilename client-supplied display filename (retained for display only); optional.
 * @param contentType      declared MIME type the signed PUT will bind; required.
 * @param sizeBytes        declared object size in bytes (used for max-size enforcement); required.
 */
public record UploadUrlRequest(
        @NotBlank @Size(max = 48) String ownerType,
        @Size(max = 255) String originalFilename,
        @NotBlank @Size(max = 128) String contentType,
        @NotNull @Positive Long sizeBytes
) {
}
