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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
 * Unit tests for {@link MediaService} — the EI-8 quarantine-then-serve invariants (PRD §21 EI-8, §18).
 *
 * <p>Responsibility: proves, with no Docker/DB, the load-bearing rules: an upload is created in
 * {@code PENDING}/quarantine with a server-generated key and the uploader taken from the principal; a
 * download URL is issued <b>only</b> for a CLEAN object and refused otherwise; the scan callback drives
 * the correct transition; and a {@code PENDING} verdict is rejected. Each test that asserts a guard is
 * written so it would <b>fail if the guard were removed</b> (e.g. {@code download_pending_refused}).</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock private MediaObjectRepository repository;
    @Mock private ObjectStore objectStore;
    // Real mapper — it is pure and cheap; testing the actual DTO shape is more valuable than a mock.
    private final MediaMapper mapper = new MediaMapper();

    private MediaService service;

    private final UUID uploader = UUID.randomUUID();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new MediaService(repository, objectStore, mapper);
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
    void getDownloadUrl_cleanObject_signsDownload() {
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
        // THE EI-8 SERVING GUARD: a non-CLEAN object must never yield a URL. If the guard in the service
        // were removed, presignDownload would be called and this test would fail — that is the point.
        MediaObject media = new MediaObject("REPORT", UUID.randomUUID(), "quarantine/2026/06/x",
                "f.jpg", "image/jpeg", 1L, uploader); // PENDING by construction
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> service.getDownloadUrl(media.getPublicId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(objectStore, never()).presignDownload(anyString(), any(Duration.class));
    }

    @Test
    void getDownloadUrl_infectedObject_isRefused_andNeverSigned() {
        MediaObject media = new MediaObject("REPORT", UUID.randomUUID(), "quarantine/2026/06/y",
                "f.jpg", "image/jpeg", 1L, uploader);
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
        UUID unknown = UUID.randomUUID();
        when(repository.findByPublicId(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDownloadUrl(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void applyScanVerdict_clean_promotesToServable_andFlagsExifStripped() {
        MediaObject media = new MediaObject("REPORT", UUID.randomUUID(), "quarantine/2026/06/z",
                "f.jpg", "image/jpeg", 1L, uploader);
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));

        MediaObjectDto dto = service.applyScanVerdict(media.getPublicId(), ScanStatus.CLEAN);

        assertThat(dto.scanStatus()).isEqualTo("CLEAN");
        assertThat(dto.servable()).isTrue();
        // EXIF/geo strip seam: CLEAN promotion flags the privacy pass (EI-8, §18).
        assertThat(dto.exifStripped()).isTrue();
    }

    @Test
    void applyScanVerdict_infected_isNotServable_andNotStripped() {
        MediaObject media = new MediaObject("REPORT", UUID.randomUUID(), "quarantine/2026/06/i",
                "f.jpg", "image/jpeg", 1L, uploader);
        when(repository.findByPublicId(media.getPublicId())).thenReturn(Optional.of(media));

        MediaObjectDto dto = service.applyScanVerdict(media.getPublicId(), ScanStatus.INFECTED);

        assertThat(dto.scanStatus()).isEqualTo("INFECTED");
        assertThat(dto.servable()).isFalse();
        assertThat(dto.exifStripped()).isFalse();
    }

    @Test
    void applyScanVerdict_pending_isRejectedAsBadRequest() {
        // PENDING is a pre-scan state, never a scan RESULT — a callback claiming it is a bad request.
        assertThatThrownBy(() -> service.applyScanVerdict(UUID.randomUUID(), ScanStatus.PENDING))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        // The record is never even loaded for an invalid verdict.
        verify(repository, never()).findByPublicId(any());
    }

    /** Builds a persisted-looking CLEAN media object (with a publicId) for serve-path tests. */
    private MediaObject cleanMedia() {
        MediaObject media = new MediaObject("REPORT", UUID.randomUUID(), "quarantine/2026/06/clean",
                "evidence.jpg", "image/jpeg", 2048L, uploader);
        media.applyScanVerdict(ScanStatus.CLEAN);
        assignPublicId(media, UUID.randomUUID());
        return media;
    }

    /**
     * Sets the inherited {@code publicId} on a transient entity for unit tests.
     *
     * <p>WHY reflection: {@code BaseEntity.publicId} is normally assigned in {@code @PrePersist}, which a
     * Mockito unit test (no EntityManager) never triggers. The serve/callback paths look the object up by
     * {@code publicId}, so the test must supply one without standing up a database (KISS — the DB-backed
     * path is covered separately by the Testcontainers IT).</p>
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
