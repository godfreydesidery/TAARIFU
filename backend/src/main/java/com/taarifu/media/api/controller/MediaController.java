package com.taarifu.media.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.media.api.dto.DownloadUrlDto;
import com.taarifu.media.api.dto.MediaObjectDto;
import com.taarifu.media.api.dto.ScanCallbackRequest;
import com.taarifu.media.api.dto.UploadRequest;
import com.taarifu.media.api.dto.UploadTicketDto;
import com.taarifu.media.api.dto.UploadUrlRequest;
import com.taarifu.media.application.service.MediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST surface for media uploads, downloads, and scan callbacks (PRD §21 EI-8, §18,
 * ARCHITECTURE.md §3.3/§5).
 *
 * <p>Responsibility: the thin HTTP layer for the media quarantine-then-serve flow — request a pre-signed
 * upload URL, fetch a pre-signed download URL (only for scanned-CLEAN objects), and receive the
 * asynchronous scan verdict. Controllers validate, delegate to {@link MediaService}, and wrap results in
 * the single {@link ApiResponse} envelope; no business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization.</b> Every endpoint is method-secured (deny-by-default — ARCHITECTURE.md §6.2).
 * Upload and download require an authenticated caller; <b>host-level</b> ownership/visibility checks
 * ("may this caller attach to / view this Report?") belong to the host module and are marked
 * {@code // TODO(wiring)} in the service, since the host lives in another module this one must not import
 * (ARCHITECTURE.md §3.2).</p>
 *
 * <p><b>Scan callback.</b> The verdict callback is authenticated like every non-public endpoint. WHY it
 * is not {@code permitAll()}: it mutates servability and must not be open to the internet. A dedicated
 * scanner <i>service principal</i> (machine credential) and/or an HMAC webhook-signature check is the
 * correct production guard — both are CENTRAL INTEGRATION NEEDS (a new role/principal and a public-path
 * decision are outside this module's allowed edits). Until then it requires authentication.</p>
 */
@RestController
@RequestMapping(path = "/media")
@Tag(name = "Media", description = "Attachments: pre-signed upload/download with quarantine-then-serve scanning (EI-8).")
public class MediaController {

    private final MediaService mediaService;
    private final ResponseFactory responses;

    /**
     * @param mediaService the media application service.
     * @param responses    the envelope builder.
     */
    public MediaController(MediaService mediaService, ResponseFactory responses) {
        this.mediaService = mediaService;
        this.responses = responses;
    }

    /**
     * Requests a pre-signed upload URL for a new attachment. Persists a {@code PENDING}/quarantined
     * record and returns the PUT URL the client uses to upload bytes directly to object storage.
     *
     * @param request the validated upload intent (host reference + declared metadata).
     * @return an envelope carrying the {@link UploadTicketDto}.
     */
    @PostMapping("/uploads")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Request a pre-signed upload URL",
            description = "Creates a PENDING (quarantined) media record and returns a pre-signed PUT. "
                    + "Bytes go straight to object storage; the object is not servable until scanned CLEAN (EI-8).")
    public ApiResponse<UploadTicketDto> requestUpload(@Valid @RequestBody UploadRequest request) {
        return responses.ok(mediaService.requestUpload(request));
    }

    /**
     * Requests a pre-signed upload URL in the citizen evidence-attachment pipeline ({@code upload-url}).
     * Persists a {@code PENDING}, <b>unbound</b>, not-yet-confirmed record and returns the PUT URL; the
     * media object is bound to its host report at file time by the reporting module.
     *
     * @param request the validated upload-url intent (host type + declared metadata; no host id yet).
     * @return an envelope carrying the {@link UploadTicketDto}.
     */
    @PostMapping("/upload-url")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Request a pre-signed upload URL (attachment pipeline)",
            description = "Creates a PENDING/unbound media record and returns a pre-signed PUT. Content-type "
                    + "allow-list + max size are checked here (fail fast) and authoritatively at confirm.")
    public ApiResponse<UploadTicketDto> requestUploadUrl(@Valid @RequestBody UploadUrlRequest request) {
        return responses.ok(mediaService.requestUploadUrl(request));
    }

    /**
     * Confirms a completed upload ({@code confirm}). Validates the declared content-type allow-list + max
     * size, marks the object uploaded, and makes it scan-eligible. Only the uploader may confirm.
     *
     * @param publicId the media object's public id.
     * @return an envelope carrying the resulting {@link MediaObjectDto} (now {@code uploaded=true}).
     */
    @PostMapping("/{publicId}/confirm")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Confirm an upload (validate content-type + size)",
            description = "Marks the object uploaded after the pre-signed PUT; enforces the content-type "
                    + "allow-list and max size; rejects a non-owner (403). Idempotent on retry.")
    public ApiResponse<MediaObjectDto> confirm(@PathVariable UUID publicId) {
        return responses.ok(mediaService.confirm(publicId));
    }

    /**
     * Fetches an access-controlled pre-signed GET for a media object ({@code GET /media/{publicId}}).
     * Succeeds only for a scanned-CLEAN object; a non-servable object yields a {@code 409 CONFLICT}
     * envelope (EI-8 serving rule). This is the canonical read endpoint for the attachment pipeline.
     *
     * @param publicId the media object's public id.
     * @return an envelope carrying the {@link DownloadUrlDto}.
     */
    @GetMapping("/{publicId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get an access-controlled pre-signed GET",
            description = "Issues a short-lived GET only for a CLEAN object; PENDING/INFECTED/FAILED are refused (EI-8). "
                    + "Host-level visibility is enforced by the host module (wiring).")
    public ApiResponse<DownloadUrlDto> getMedia(@PathVariable UUID publicId) {
        return responses.ok(mediaService.getDownloadUrl(publicId));
    }

    /**
     * Fetches a pre-signed download URL for a media object. Succeeds only for a scanned-CLEAN object;
     * a non-servable object yields a {@code 409 CONFLICT} envelope (EI-8 serving rule).
     *
     * @param mediaId the media object's public id.
     * @return an envelope carrying the {@link DownloadUrlDto}.
     */
    @GetMapping("/{mediaId}/download-url")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a pre-signed download URL",
            description = "Issues a short-lived GET only for a CLEAN object; PENDING/INFECTED/FAILED are refused (EI-8). "
                    + "Host-level visibility is enforced by the host module (wiring).")
    public ApiResponse<DownloadUrlDto> getDownloadUrl(@PathVariable UUID mediaId) {
        return responses.ok(mediaService.getDownloadUrl(mediaId));
    }

    /**
     * Receives the asynchronous scan verdict for a quarantined object and applies the resulting state
     * transition (on CLEAN the object becomes servable).
     *
     * @param mediaId the media object's public id.
     * @param request the validated verdict body.
     * @return an envelope carrying the resulting {@link MediaObjectDto}.
     */
    @PostMapping("/{mediaId}/scan-callback")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Apply a scan verdict (callback)",
            description = "Transitions PENDING → CLEAN/INFECTED/FAILED. Authenticated; production should additionally "
                    + "require a scanner service principal and/or HMAC signature (central need).")
    public ApiResponse<MediaObjectDto> scanCallback(@PathVariable UUID mediaId,
                                                    @Valid @RequestBody ScanCallbackRequest request) {
        return responses.ok(mediaService.applyScanVerdict(mediaId, request.verdict()));
    }
}
