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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MediaService} — the EI-8 quarantine-then-serve invariants plus the attachment
 * pipeline's upload-url / confirm policy gates (PRD §21 EI-8, §15, §18).
 *
 * <p>Responsibility: proves, with no Docker/DB, the load-bearing rules: an upload is created in
 * {@code PENDING}/quarantine with a server-generated key and the uploader taken from the principal; the
 * pipeline upload-url creates an <b>unbound</b> record and rejects a disallowed type/size up front; confirm
 * enforces the allow-list + max size and refuses a non-owner; a download URL is issued <b>only</b> for a
 * CLEAN object and refused otherwise; the scan callback drives the correct transition; and a {@code PENDING}
 * verdict is rejected. Each guard test is written so it would <b>fail if the guard were removed</b>.</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock private MediaObjectRepository repository;
    @Mock private ObjectStore objectStore;
    // The reporting host-visibility port (MF-2). Mocked so the media unit test can drive every branch of the
    // serve-path access gate without standing up the reporting module.
    @Mock private ReportMediaAccessApi reportMediaAccess;
    // Real mapper — it is pure and cheap; testing the actual DTO shape is more valuable than a mock.
    private final MediaMapper mapper = new MediaMapper();
    // Real policy with defaults (image/* allow-list, 10 MiB cap) — exercises the actual policy logic.
    private final MediaPolicyProperties policy = new MediaPolicyProperties(null, null);

    private MediaService service;

    private final UUID uploader = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MediaService(repository, objectStore, mapper, policy, reportMediaAccess);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Places an authenticated CurrentUser in the security context (mirrors the JWT filter's wiring). */
    private void authenticateAs(UUID principal) {
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, "n/a", List.of());
        ((UsernamePasswordAuthenticationToken) auth)
                .setDetails(new CurrentUser(principal, List.of("CITIZEN"), "T1"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ---------------------------------------------------------------------------------------------
    // requestUpload (legacy, bound) + requestUploadUrl (pipeline, unbound)
    // ---------------------------------------------------------------------------------------------

    @Test
    void requestUpload_createsPendingQuarantinedRecord_andSignsUpload() {
        authenticateAs(uploader);
        UUID ownerId = UUID.randomUUID();
        UploadRequest req = new UploadRequest("REPORT", ownerId, "evidence.jpg", "image/jpeg", 1024L);

        when(repository.save(any(MediaObject.class))).thenAnswer(inv -> inv.getArgument(0));
        when(objectStore.presignUpload(anyString(), eq("image/jpeg"), any(Duration.class)))
                .thenReturn(new ObjectStore.PresignedUrl("https://store/put", "PUT",
                        Map.of("Content-Type", "image/jpeg"), 900));

        UploadTicketDto ticket = service.requestUpload(req);

        ArgumentCaptor<MediaObject> saved = ArgumentCaptor.forClass(MediaObject.class);
        verify(repository).save(saved.capture());
        MediaObject media = saved.getValue();
        // New uploads ALWAYS start PENDING/quarantine — never servable on creation (EI-8).
        assertThat(media.getScanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(media.isServable()).isFalse();
        // The key is server-generated into the quarantine prefix — not derived from the client filename.
        assertThat(media.getObjectKey()).startsWith("quarantine/");
        assertThat(media.getObjectKey()).doesNotContain("evidence.jpg");
        // The uploader comes from the authenticated principal, never the request body (no spoofing).
        assertThat(media.getUploadedByProfileId()).isEqualTo(uploader);
        assertThat(ticket.method()).isEqualTo("PUT");
        assertThat(ticket.uploadUrl()).isEqualTo("https://store/put");
    }

    @Test
    void requestUploadUrl_createsUnboundPendingRecord_andSignsUpload() {
        authenticateAs(uploader);
        UploadUrlRequest req = new UploadUrlRequest("REPORT", "evidence.jpg", "image/jpeg", 1024L);

        when(repository.save(any(MediaObject.class))).thenAnswer(inv -> inv.getArgument(0));
        when(objectStore.presignUpload(anyString(), eq("image/jpeg"), any(Duration.class)))
                .thenReturn(new ObjectStore.PresignedUrl("https://store/put", "PUT",
                        Map.of("Content-Type", "image/jpeg"), 900));

        service.requestUploadUrl(req);

        ArgumentCaptor<MediaObject> saved = ArgumentCaptor.forClass(MediaObject.class);
        verify(repository).save(saved.capture());
        MediaObject media = saved.getValue();
        // The pipeline upload is UNBOUND (no host id) and not-yet-confirmed/uploaded.
        assertThat(media.getOwnerId()).isNull();
        assertThat(media.isUploaded()).isFalse();
        assertThat(media.getScanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(media.getUploadedByProfileId()).isEqualTo(uploader);
    }

    @Test
    void requestUploadUrl_disallowedContentType_isRejected_andNothingSaved() {
        authenticateAs(uploader);
        // A PDF (or any non-image) is not on the default allow-list — reject before issuing a URL.
        UploadUrlRequest req = new UploadUrlRequest("REPORT", "doc.pdf", "application/pdf", 1024L);

        assertThatThrownBy(() -> service.requestUploadUrl(req))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);

        verify(repository, never()).save(any());
        verify(objectStore, never()).presignUpload(anyString(), any(), any());
    }

    @Test
    void requestUploadUrl_oversizeObject_isRejected() {
        authenticateAs(uploader);
        // 11 MiB exceeds the default 10 MiB cap.
        UploadUrlRequest req = new UploadUrlRequest("REPORT", "huge.jpg", "image/jpeg", 11L * 1024 * 1024);

        assertThatThrownBy(() -> service.requestUploadUrl(req))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(repository, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------------
    // confirm — the authoritative policy + owner gate
    // ---------------------------------------------------------------------------------------------

    @Test
    void confirm_byUploader_marksUploaded() {
        authenticateAs(uploader);
        MediaObject media = pendingUnbound("image/jpeg", 2048L);
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));

        MediaObjectDto dto = service.confirm(media.getPublicId());

        assertThat(media.isUploaded()).isTrue();
        assertThat(dto.scanStatus()).isEqualTo("PENDING"); // confirmed, awaiting scan
        assertThat(dto.servable()).isFalse();
    }

    @Test
    void confirm_byNonUploader_isForbidden() {
        // THE OWNER GUARD: a different principal must not confirm someone else's upload. If the guard were
        // removed the call would succeed — this test would then fail, which is the point.
        authenticateAs(UUID.randomUUID());
        MediaObject media = pendingUnbound("image/jpeg", 2048L);
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> service.confirm(media.getPublicId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(media.isUploaded()).isFalse();
    }

    @Test
    void confirm_disallowedContentType_isRejected() {
        // THE POLICY GUARD AT CONFIRM: even if a bad type slipped through, confirm is authoritative.
        authenticateAs(uploader);
        MediaObject media = pendingUnbound("application/zip", 2048L);
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> service.confirm(media.getPublicId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(media.isUploaded()).isFalse();
    }

    @Test
    void confirm_unknownId_throwsNotFound() {
        authenticateAs(uploader);
        UUID unknown = UUID.randomUUID();
        when(repository.findByPublicId(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // getDownloadUrl — the EI-8 serving gate
    // ---------------------------------------------------------------------------------------------

    @Test
    void getDownloadUrl_byUploader_cleanObject_signsDownload() {
        // The uploader may always view their own object (the mayView uploader branch).
        authenticateAs(uploader);
        MediaObject media = cleanMedia();
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));
        when(objectStore.presignDownload(eq(media.getObjectKey()), any(Duration.class)))
                .thenReturn(new ObjectStore.PresignedUrl("https://store/get", "GET", Map.of(), 300));

        DownloadUrlDto dto = service.getDownloadUrl(media.getPublicId());

        assertThat(dto.downloadUrl()).isEqualTo("https://store/get");
        assertThat(dto.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void getDownloadUrl_pendingObject_isRefused_andNeverSigned() {
        // THE EI-8 SERVING GUARD: a non-CLEAN object must never yield a URL (caller is the uploader, so the
        // access gate passes and we reach the scan-state gate).
        authenticateAs(uploader);
        MediaObject media = pendingUnbound("image/jpeg", 1L);
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> service.getDownloadUrl(media.getPublicId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(objectStore, never()).presignDownload(anyString(), any(Duration.class));
    }

    @Test
    void getDownloadUrl_infectedObject_isRefused_andNeverSigned() {
        authenticateAs(uploader);
        MediaObject media = pendingUnbound("image/jpeg", 1L);
        media.applyScanVerdict(ScanStatus.INFECTED);
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> service.getDownloadUrl(media.getPublicId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(objectStore, never()).presignDownload(anyString(), any(Duration.class));
    }

    @Test
    void getDownloadUrl_unknownId_throwsNotFound() {
        authenticateAs(uploader);
        UUID unknown = UUID.randomUUID();
        when(repository.findByPublicId(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDownloadUrl(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // getDownloadUrl — the MF-2 host-visibility access gate (IDOR / OWASP A01)
    // ---------------------------------------------------------------------------------------------

    @Test
    void getDownloadUrl_reportMedia_authorizedByHost_signsDownload() {
        // A non-uploader (e.g. the reporter, or an in-scope responder) the reporting visibility port GRANTS
        // may download CLEAN evidence bound to a report.
        UUID viewer = UUID.randomUUID();
        authenticateAs(viewer);
        MediaObject media = cleanReportMedia(UUID.randomUUID());
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));
        when(reportMediaAccess.canViewReportMedia(media.getOwnerId(), viewer)).thenReturn(true);
        when(objectStore.presignDownload(eq(media.getObjectKey()), any(Duration.class)))
                .thenReturn(new ObjectStore.PresignedUrl("https://store/get", "GET", Map.of(), 300));

        DownloadUrlDto dto = service.getDownloadUrl(media.getPublicId());

        assertThat(dto.downloadUrl()).isEqualTo("https://store/get");
    }

    @Test
    void getDownloadUrl_reportMedia_deniedByHost_isForbidden_andNeverSigned() {
        // THE MF-2 IDOR GUARD: a non-uploader the host DENIES (a private/anonymous-sensitive report's
        // evidence) must get 403 and never a signed URL — even though the object is CLEAN. If the gate were
        // removed the scan-state check alone would mint a URL — this test would then fail, which is the point.
        UUID stranger = UUID.randomUUID();
        authenticateAs(stranger);
        MediaObject media = cleanReportMedia(UUID.randomUUID());
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));
        when(reportMediaAccess.canViewReportMedia(media.getOwnerId(), stranger)).thenReturn(false);

        assertThatThrownBy(() -> service.getDownloadUrl(media.getPublicId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(objectStore, never()).presignDownload(anyString(), any(Duration.class));
    }

    @Test
    void getDownloadUrl_reportMedia_byUploader_skipsHostCheck_signsDownload() {
        // The uploader short-circuits the host check (the uploader branch of mayView): the visibility port is
        // never consulted, and the CLEAN object is served.
        authenticateAs(uploader);
        MediaObject media = cleanReportMedia(UUID.randomUUID());
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));
        when(objectStore.presignDownload(eq(media.getObjectKey()), any(Duration.class)))
                .thenReturn(new ObjectStore.PresignedUrl("https://store/get", "GET", Map.of(), 300));

        DownloadUrlDto dto = service.getDownloadUrl(media.getPublicId());

        assertThat(dto.downloadUrl()).isEqualTo("https://store/get");
        verify(reportMediaAccess, never()).canViewReportMedia(any(), any());
    }

    @Test
    void getDownloadUrl_unboundReportMedia_nonUploader_isForbidden() {
        // Deny-by-default: an object not bound to a host yet (ownerId == null) is viewable only by its
        // uploader; a stranger is refused without ever consulting the host port.
        UUID stranger = UUID.randomUUID();
        authenticateAs(stranger);
        MediaObject media = cleanMedia(); // ownerType=REPORT but ownerId is null (unbound)
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> service.getDownloadUrl(media.getPublicId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(reportMediaAccess, never()).canViewReportMedia(any(), any());
        verify(objectStore, never()).presignDownload(anyString(), any(Duration.class));
    }

    // ---------------------------------------------------------------------------------------------
    // applyScanVerdict
    // ---------------------------------------------------------------------------------------------

    @Test
    void applyScanVerdict_clean_promotesToServable_andFlagsExifStripped() {
        MediaObject media = pendingUnbound("image/jpeg", 1L);
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));

        MediaObjectDto dto = service.applyScanVerdict(media.getPublicId(), ScanStatus.CLEAN);

        assertThat(dto.scanStatus()).isEqualTo("CLEAN");
        assertThat(dto.servable()).isTrue();
        assertThat(dto.exifStripped()).isTrue();
    }

    @Test
    void applyScanVerdict_infected_isNotServable_andNotStripped() {
        MediaObject media = pendingUnbound("image/jpeg", 1L);
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));

        MediaObjectDto dto = service.applyScanVerdict(media.getPublicId(), ScanStatus.INFECTED);

        assertThat(dto.scanStatus()).isEqualTo("INFECTED");
        assertThat(dto.servable()).isFalse();
        assertThat(dto.exifStripped()).isFalse();
    }

    @Test
    void applyScanVerdict_pending_isRejectedAsBadRequest() {
        assertThatThrownBy(() -> service.applyScanVerdict(UUID.randomUUID(), ScanStatus.PENDING))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(repository, never()).findByPublicId(any());
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /** Builds a transient PENDING, unbound media object (publicId assigned) uploaded by {@link #uploader}. */
    private MediaObject pendingUnbound(String contentType, long sizeBytes) {
        MediaObject media = new MediaObject("REPORT", null,
                "quarantine/2026/06/" + UUID.randomUUID(), "f.jpg", contentType, sizeBytes, uploader);
        assignPublicId(media, UUID.randomUUID());
        return media;
    }

    /** Builds a persisted-looking CLEAN media object (with a publicId, unbound) for serve-path tests. */
    private MediaObject cleanMedia() {
        MediaObject media = pendingUnbound("image/jpeg", 2048L);
        media.applyScanVerdict(ScanStatus.CLEAN);
        return media;
    }

    /**
     * Builds a CLEAN media object bound to a {@code REPORT} host (ownerType=REPORT, ownerId set) — the case
     * the MF-2 host-visibility gate delegates to the reporting visibility port.
     *
     * @param reportId the bound report's public id.
     */
    private MediaObject cleanReportMedia(UUID reportId) {
        MediaObject media = new MediaObject("REPORT", null,
                "quarantine/2026/06/" + UUID.randomUUID(), "f.jpg", "image/jpeg", 2048L, uploader);
        media.bindTo(reportId);
        media.applyScanVerdict(ScanStatus.CLEAN);
        assignPublicId(media, UUID.randomUUID());
        return media;
    }

    /**
     * Sets the inherited {@code publicId} on a transient entity for unit tests.
     *
     * <p>WHY reflection: {@code BaseEntity.publicId} is normally assigned in {@code @PrePersist}, which a
     * Mockito unit test (no EntityManager) never triggers. The serve/confirm/callback paths look the object
     * up by {@code publicId}, so the test must supply one without standing up a database.</p>
     */
    private static void assignPublicId(MediaObject media, UUID id) {
        try {
            var f = com.taarifu.common.domain.model.BaseEntity.class.getDeclaredField("publicId");
            f.setAccessible(true);
            f.set(media, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set publicId for test", e);
        }
    }
}
