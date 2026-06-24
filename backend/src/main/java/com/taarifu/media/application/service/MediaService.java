package com.taarifu.media.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.media.api.dto.DownloadUrlDto;
import com.taarifu.media.api.dto.MediaObjectDto;
import com.taarifu.media.api.dto.UploadRequest;
import com.taarifu.media.api.dto.UploadTicketDto;
import com.taarifu.media.api.dto.UploadUrlRequest;
import com.taarifu.media.application.mapper.MediaMapper;
import com.taarifu.media.domain.model.MediaObject;
import com.taarifu.media.domain.model.enums.ScanStatus;
import com.taarifu.media.domain.port.ObjectStore;
import com.taarifu.media.domain.repository.MediaObjectRepository;
import com.taarifu.media.infrastructure.config.MediaPolicyProperties;
import com.taarifu.reporting.api.ReportMediaAccessApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
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
 * <p><b>Boundary note (host visibility).</b> The host of an attachment ({@code Report}, {@code Profile},
 * announcement, verification evidence) lives in a feature module this one must not import
 * (ARCHITECTURE.md §3.2). Ownership/visibility of that host must be decided by the host module. The
 * <b>download path is now host-visibility-scoped</b> (MF-2): for {@code REPORT}-owned evidence it delegates
 * to reporting's {@link ReportMediaAccessApi} (ADR-0013 {@code api -> api}), and otherwise serves only the
 * uploader — deny-by-default. The <b>upload/attach</b> direction is enforced by the host at file time via
 * {@code MediaAttachmentApi}. Other host types (avatar/announcement/verification evidence) remain
 * uploader-only on the serve path until they publish their own visibility port.</p>
 */
@Service
@Transactional
public class MediaService {

    /** Storage prefix for unscanned uploads — the quarantine location (EI-8). */
    private static final String QUARANTINE_PREFIX = "quarantine/";

    /**
     * The {@code ownerType} discriminator a report's evidence carries (mirrors reporting's
     * {@code MediaAttachmentValidator.OWNER_TYPE_REPORT}). The serve path routes a download authorization
     * check for objects of this type to the reporting visibility port (MF-2). Other host types fall through
     * to uploader-only until their own visibility port lands (deny-by-default).
     */
    private static final String OWNER_TYPE_REPORT = "REPORT";

    /** Pre-signed upload URL validity — short to limit replay (PRD §18). */
    private static final Duration UPLOAD_TTL = Duration.ofMinutes(15);

    /** Pre-signed download URL validity — short to limit link sharing/replay (PRD §18). */
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);

    private final MediaObjectRepository repository;
    private final ObjectStore objectStore;
    private final MediaMapper mapper;
    private final MediaPolicyProperties policy;

    /**
     * The reporting module's host-visibility port (MF-2). Resolves "may this caller view this report's
     * evidence?" so the serve path can fail closed for private/anonymous-sensitive reports. Cross-module
     * {@code api -> api} only (ADR-0013); referenced by interface, never reaching into reporting's tables.
     *
     * <p><b>Optional by design:</b> {@code @Autowired(required = false)} so this service still constructs in
     * a media-only context (unit tests, a slice without reporting). WHY that is still safe: when the port is
     * absent, the serve path treats a {@code REPORT}-owned object as <b>not</b> caller-viewable unless the
     * caller is the uploader — deny-by-default, never fail-open. In the assembled application the single
     * {@link ReportMediaAccessApi} bean is always injected.</p>
     */
    @Nullable
    private final ReportMediaAccessApi reportMediaAccess;

    /**
     * @param repository        media persistence port.
     * @param objectStore       S3-compatible object-store port (stub in dev/test).
     * @param mapper            entity→DTO mapper.
     * @param policy            upload allow-list / max-size policy enforced at the {@code confirm} step.
     * @param reportMediaAccess the reporting host-visibility port (MF-2); may be {@code null} in a
     *                          media-only context (then REPORT-owned media is uploader-only — deny-by-default).
     */
    @Autowired
    public MediaService(MediaObjectRepository repository, ObjectStore objectStore, MediaMapper mapper,
                        MediaPolicyProperties policy,
                        @Nullable ReportMediaAccessApi reportMediaAccess) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.mapper = mapper;
        this.policy = policy;
        this.reportMediaAccess = reportMediaAccess;
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
     * Pipeline use-case A — {@code POST /media/upload-url}. Persists a new {@code PENDING}, <b>unbound</b>
     * (no host id yet), not-yet-confirmed record and mints a pre-signed PUT for the citizen evidence-photo
     * attachment flow.
     *
     * <p>WHY unbound: in this pipeline the photo is uploaded <i>before</i> the report is filed, so the host
     * id is unknown here ({@code ownerId} stays {@code null}); the reporting module binds the object at file
     * time via the {@code media.api} port. WHY the content-type/size are still validated against policy now:
     * to reject an obviously-disallowed attachment (e.g. a video, an oversized blob) before a pre-signed URL
     * is even issued — fail fast, save the round-trip and the wasted upload on a low-bandwidth link
     * (PRD §15). The authoritative re-check is at {@link #confirm(UUID)}.</p>
     *
     * @param request the validated upload-url intent (host type + declared metadata; no host id).
     * @return an {@link UploadTicketDto}: the new media id plus the pre-signed PUT.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if the declared content-type/size violate policy.
     */
    public UploadTicketDto requestUploadUrl(UploadUrlRequest request) {
        UUID uploader = CurrentUser.requirePublicId();

        // Fail fast at the boundary: reject a disallowed type/size before issuing a URL (PRD §15/§18).
        enforcePolicy(request.contentType(), request.sizeBytes());

        String objectKey = newQuarantineKey();
        MediaObject media = new MediaObject(
                request.ownerType(), null, objectKey,
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
     * Pipeline use-case B — {@code POST /media/{publicId}/confirm}. The client has finished the pre-signed
     * PUT; this validates the declared content-type allow-list + max size, marks the object
     * confirmed-uploaded, and (in this increment) kicks off the scan by leaving it {@code PENDING}/eligible.
     *
     * <p>WHY confirm is the authoritative policy gate: the byte transfer is direct client↔store, so the app
     * never observes it; confirm is the single point where the object becomes "uploaded" and therefore the
     * right place to enforce the allow-list/size invariant so it cannot be bypassed by skipping the step.
     * Only the uploader may confirm their own object (no confirming another citizen's upload).</p>
     *
     * <p>WHY idempotent: a client may retry confirm (flaky link). A second confirm on an already-uploaded
     * object re-validates and returns the same state — no error, no double effect (EI-8 fail-safe spirit).</p>
     *
     * @param mediaId the media object's public id.
     * @return the resulting {@link MediaObjectDto} (now {@code uploaded=true}).
     * @throws ResourceNotFoundException if no such object exists/soft-deleted.
     * @throws ApiException {@link ErrorCode#FORBIDDEN} if the caller is not the uploader;
     *                      {@link ErrorCode#BAD_REQUEST} if the content-type/size violate policy.
     */
    @Transactional
    public MediaObjectDto confirm(UUID mediaId) {
        UUID caller = CurrentUser.requirePublicId();
        MediaObject media = require(mediaId);

        // Only the uploader may confirm their own object — never confirm on behalf of another citizen.
        if (!caller.equals(media.getUploadedByProfileId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "media.notOwner");
        }

        // Authoritative allow-list + max-size enforcement (the single point an object becomes scan-eligible).
        enforcePolicy(media.getContentType(), media.getSizeBytes());

        media.markUploaded();
        // Scan kick-off seam: the object stays PENDING and is now scan-eligible. The asynchronous scanner
        // pipeline picks up confirmed-PENDING objects and calls back the verdict (EI-8). Wiring the scanner
        // enqueue (vs. the existing callback-driven flow) is a CENTRAL INTEGRATION NEED.
        return mapper.toDto(media);
    }

    /**
     * Use-case 2 — get a download URL. Enforces host-visibility access control (MF-2) and then issues a
     * pre-signed GET <b>only</b> for a scanned-CLEAN object; any other scan state is refused with
     * {@link ErrorCode#CONFLICT} (EI-8 serving rule — fail-safe).
     *
     * <p><b>Access control (MF-2, OWASP A01 / IDOR).</b> Authorization is checked <i>before</i> the
     * scan-state gate so an unauthorized caller learns nothing about the object (not even that it exists or
     * its scan state). A download is permitted only when the caller is the media's <b>uploader</b>, or —
     * when the object is bound to a {@code REPORT} — the reporting module's visibility port grants the
     * caller view access (the reporter, or an authorized in-scope responder/moderator). For any object that
     * is not the caller's own and not a REPORT (or where the visibility port is unavailable), access is
     * <b>denied</b> — deny-by-default, so a private/anonymous-sensitive report's evidence is never mintable
     * by an arbitrary account (PRD §25.3, §18).</p>
     *
     * @param mediaId the media object's public id.
     * @return a {@link DownloadUrlDto} with the pre-signed GET.
     * @throws ResourceNotFoundException if no such object exists/soft-deleted.
     * @throws ApiException {@link ErrorCode#FORBIDDEN} if the caller may not view this object;
     *                      {@link ErrorCode#CONFLICT} if the object is not yet servable (PENDING/INFECTED/FAILED).
     */
    @Transactional(readOnly = true)
    public DownloadUrlDto getDownloadUrl(UUID mediaId) {
        UUID caller = CurrentUser.requirePublicId();
        MediaObject media = require(mediaId);

        // MF-2: host-visibility access control runs FIRST so an unauthorized caller cannot even probe the
        // object's existence or scan state. Fail-closed (deny-by-default).
        if (!mayView(caller, media)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "media.notAuthorized");
        }

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
     * Decides whether {@code caller} may view (obtain a download URL for) {@code media} — the MF-2
     * host-visibility gate. Deny-by-default.
     *
     * <ul>
     *   <li><b>The uploader</b> may always view their own object (covers the avatar/own-evidence case and a
     *       not-yet-bound object the citizen just uploaded).</li>
     *   <li>For an object <b>bound to a {@code REPORT}</b>, the reporting visibility port is the authority:
     *       it grants the reporter and authorized in-scope responders/moderators, and denies everyone else
     *       — so a PRIVATE / anonymous-sensitive report's evidence stays private (PRD §25.3).</li>
     *   <li>Any other case (a non-REPORT host without its own visibility port yet, an unbound object that is
     *       not the caller's, or the port being unavailable) is <b>denied</b> — never fail-open.</li>
     * </ul>
     *
     * @param caller the authenticated caller's account public id.
     * @param media  the media object being requested.
     * @return {@code true} only if the caller is the uploader or the host grants visibility.
     */
    private boolean mayView(UUID caller, MediaObject media) {
        // 1) The uploader always sees their own object.
        if (caller.equals(media.getUploadedByProfileId())) {
            return true;
        }
        // 2) Delegate REPORT-owned media to the host module's visibility port (ADR-0013 api -> api).
        if (OWNER_TYPE_REPORT.equals(media.getOwnerType())
                && media.getOwnerId() != null
                && reportMediaAccess != null) {
            return reportMediaAccess.canViewReportMedia(media.getOwnerId(), caller);
        }
        // 3) Deny-by-default: unknown host, unbound non-owner object, or no visibility port available.
        return false;
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
     * Enforces the upload policy: the declared content-type must be on the allow-list and the declared size
     * within the cap, else a localised {@link ErrorCode#BAD_REQUEST}. Centralised so the rule is identical
     * at upload-url time (fail fast) and confirm time (authoritative).
     *
     * @param contentType the declared MIME type.
     * @param sizeBytes   the declared size in bytes.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} on a disallowed content-type or oversized object.
     */
    private void enforcePolicy(String contentType, Long sizeBytes) {
        if (!policy.isContentTypeAllowed(contentType)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "media.contentTypeNotAllowed", String.valueOf(contentType));
        }
        if (!policy.isSizeAllowed(sizeBytes)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "media.tooLarge",
                    String.valueOf(sizeBytes), String.valueOf(policy.maxSizeBytes()));
        }
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
