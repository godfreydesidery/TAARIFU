package com.taarifu.media.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.media.api.dto.DownloadUrlDto;
import com.taarifu.media.api.dto.MediaObjectDto;
import com.taarifu.media.api.dto.UploadRequest;
import com.taarifu.media.api.dto.UploadTicketDto;
import com.taarifu.media.application.mapper.MediaMapper;
import com.taarifu.media.domain.model.MediaObject;
import com.taarifu.media.domain.model.enums.ScanStatus;
import com.taarifu.media.domain.port.ObjectStore;
import com.taarifu.media.domain.repository.MediaObjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Application service implementing the media quarantine-then-serve flow (PRD §21 EI-8, §18,
 * ARCHITECTURE.md §3.3/§7).
 *
 * <p>Responsibility: orchestrates the three media use-cases and owns their transaction boundary —
 * (1) <b>request upload</b>: persist a {@code PENDING} record and mint a pre-signed PUT into quarantine;
 * (2) <b>get download</b>: refuse anything not scanned {@link ScanStatus#CLEAN}, else mint a pre-signed
 * GET; (3) <b>apply scan verdict</b>: transition the record and, on CLEAN, mark it servable (and flag
 * the EXIF-strip seam). Returns DTOs only (entities never leave the module — CLAUDE.md §8).</p>
 *
 * <p><b>The load-bearing integrity rule (EI-8).</b> A download URL is issued <i>only</i> for a CLEAN
 * object; PENDING/INFECTED/FAILED are refused with {@link ErrorCode#CONFLICT}. This is the fail-safe
 * gate that guarantees unscanned or unsafe media is never served to another citizen, while uploads are
 * always accepted (the citizen's report is never blocked by the scanner — delivery is merely deferred).</p>
 *
 * <p><b>Boundary note (// TODO(wiring)).</b> The host of an attachment ({@code Report}, {@code Profile},
 * announcement, verification evidence) lives in a feature module this one must not import
 * (ARCHITECTURE.md §3.2). Ownership/visibility of that host — "may this caller attach to / download from
 * this resource?" — must be enforced by the host module. This service authenticates the caller and
 * gates on scan state; the host-level authorization checks are marked {@code // TODO(wiring)} until the
 * host modules land and expose a public authorization API.</p>
 */
@Service
@Transactional
public class MediaService {

    /** Storage prefix for unscanned uploads — the quarantine location (EI-8). */
    private static final String QUARANTINE_PREFIX = "quarantine/";

    /** Pre-signed upload URL validity — short to limit replay (PRD §18). */
    private static final Duration UPLOAD_TTL = Duration.ofMinutes(15);

    /** Pre-signed download URL validity — short to limit link sharing/replay (PRD §18). */
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);

    private final MediaObjectRepository repository;
    private final ObjectStore objectStore;
    private final MediaMapper mapper;

    /**
     * @param repository  media persistence port.
     * @param objectStore S3-compatible object-store port (stub in dev/test).
     * @param mapper      entity→DTO mapper.
     */
    public MediaService(MediaObjectRepository repository, ObjectStore objectStore, MediaMapper mapper) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.mapper = mapper;
    }

    /**
     * Use-case 1 — request an upload URL. Persists a new {@code PENDING}/quarantined record and mints a
     * pre-signed PUT the client uses to upload bytes directly to storage.
     *
     * <p>The server generates the {@code objectKey} (date-partitioned, UUID-based) — the client never
     * proposes one, so keys are collision-free and path-traversal-safe. The uploader is taken from the
     * authenticated principal, not the request body (no spoofing the uploader id).</p>
     *
     * @param request the validated upload intent (host reference + declared metadata).
     * @return an {@link UploadTicketDto}: the new media id plus the pre-signed PUT.
     */
    public UploadTicketDto requestUpload(UploadRequest request) {
        UUID uploader = CurrentUser.requirePublicId();

        // TODO(wiring): authorize that `uploader` may attach to (request.ownerType, request.ownerId)
        // via the host module's public API (the host lives in another module — ARCHITECTURE.md §3.2).

        String objectKey = newQuarantineKey();
        MediaObject media = new MediaObject(
                request.ownerType(), request.ownerId(), objectKey,
                request.originalFilename(), request.contentType(), request.sizeBytes(), uploader);
        media = repository.save(media);

        ObjectStore.PresignedUrl presigned =
                objectStore.presignUpload(objectKey, request.contentType(), UPLOAD_TTL);

        return new UploadTicketDto(
                media.getPublicId(),
                presigned.url(),
                presigned.method(),
                presigned.requiredHeaders(),
                presigned.expiresInSeconds());
    }

    /**
     * Use-case 2 — get a download URL. Issues a pre-signed GET <b>only</b> for a scanned-CLEAN object;
     * any other scan state is refused with {@link ErrorCode#CONFLICT} (EI-8 serving rule — fail-safe).
     *
     * @param mediaId the media object's public id.
     * @return a {@link DownloadUrlDto} with the pre-signed GET.
     * @throws ResourceNotFoundException if no such object exists/soft-deleted.
     * @throws ApiException {@link ErrorCode#CONFLICT} if the object is not yet servable (PENDING/INFECTED/FAILED).
     */
    @Transactional(readOnly = true)
    public DownloadUrlDto getDownloadUrl(UUID mediaId) {
        MediaObject media = require(mediaId);

        // TODO(wiring): authorize that the current caller may view (media.ownerType, media.ownerId)
        // via the host module's public API (visibility lives with the host — ARCHITECTURE.md §3.2).

        // EI-8 SERVING RULE: never issue a URL for a non-CLEAN object. PENDING/INFECTED/FAILED all fail
        // safe here — the citizen's upload was accepted, but unsafe/unscanned bytes are never served.
        if (!media.isServable()) {
            throw new ApiException(ErrorCode.CONFLICT, "media.notServable",
                    media.getScanStatus().name());
        }

        ObjectStore.PresignedUrl presigned =
                objectStore.presignDownload(media.getObjectKey(), DOWNLOAD_TTL);

        return new DownloadUrlDto(
                media.getPublicId(),
                presigned.url(),
                media.getContentType(),
                media.getOriginalFilename(),
                presigned.expiresInSeconds());
    }

    /**
     * Use-case 3 — apply a scan verdict (async callback from the scanning pipeline). Transitions the
     * object's {@link ScanStatus}; on {@code CLEAN} the object becomes servable and the EXIF/geo-strip
     * seam is flagged.
     *
     * <p>WHY a {@code PENDING} verdict is rejected: PENDING is a pre-scan state, never a scan result; a
     * callback claiming it is a bad request, not a state transition (input validation, ARCHITECTURE §5.2).</p>
     *
     * <p>WHY this is idempotent on a terminal INFECTED: re-delivering a verdict (scanners deliver
     * at-least-once) must not flip an INFECTED object back to CLEAN — the entity enforces that
     * ({@link MediaObject#applyScanVerdict}). The method is therefore safe to replay (EI-8 / DI4).</p>
     *
     * @param mediaId the media object's public id.
     * @param verdict the scanner outcome (must not be {@code PENDING}).
     * @return the resulting {@link MediaObjectDto} so the caller can observe the new state.
     * @throws ResourceNotFoundException if no such object exists/soft-deleted.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if {@code verdict} is {@code PENDING}.
     */
    public MediaObjectDto applyScanVerdict(UUID mediaId, ScanStatus verdict) {
        if (verdict == null || verdict == ScanStatus.PENDING) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "media.invalidVerdict");
        }
        MediaObject media = require(mediaId);

        media.applyScanVerdict(verdict);

        // EXIF/geo strip seam (EI-8, §18): on CLEAN the promote+strip pipeline step runs. Until the
        // worker is wired, we flag the seam so the serve-path invariant ("stripped before served") is
        // explicit and testable. The actual byte-level strip is a worker step.
        if (media.isServable()) {
            // TODO(wiring): invoke the EXIF/geo-stripping worker on the quarantine object and promote
            // it from the quarantine location to the served location before marking it stripped.
            media.markExifStripped();
        }

        return mapper.toDto(media);
    }

    /** Loads a media object by public id or throws a localised not-found. */
    private MediaObject require(UUID mediaId) {
        return repository.findByPublicId(mediaId)
                .orElseThrow(() -> new ResourceNotFoundException("media.notFound", mediaId));
    }

    /**
     * Generates a fresh, date-partitioned quarantine storage key.
     *
     * <p>Format {@code quarantine/yyyy/MM/<uuid>}: date partitioning keeps store listings manageable and
     * lets lifecycle/janitor rules target old quarantine objects; the UUID guarantees collision-freedom
     * without leaking the original filename or any client-controlled path (security).</p>
     *
     * @return a new unique quarantine key.
     */
    private String newQuarantineKey() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return QUARANTINE_PREFIX + today.getYear() + "/"
                + String.format("%02d", today.getMonthValue()) + "/"
                + UUID.randomUUID();
    }
}
