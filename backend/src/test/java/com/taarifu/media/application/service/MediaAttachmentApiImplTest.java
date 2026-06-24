package com.taarifu.media.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.media.domain.model.MediaObject;
import com.taarifu.media.domain.repository.MediaObjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MediaAttachmentApiImpl} — the cross-module attachment validate-and-bind integrity
 * invariants (ADR-0013 §4a; PRD §18 / PDPA).
 *
 * <p>Responsibility: proves, with no DB, the load-bearing rules a host module relies on when attaching a
 * citizen's media — only the citizen's <b>own</b>, <b>confirmed-uploaded</b>, correctly-<b>typed</b>,
 * not-yet-bound objects may be attached, and an <b>anonymous</b> filing may carry no account-owned media.
 * Each guard test is written so it would <b>fail if the guard were removed</b>.</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaAttachmentApiImplTest {

    @Mock private MediaObjectRepository repository;

    private MediaAttachmentApiImpl api;

    private final UUID reporter = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        api = new MediaAttachmentApiImpl(repository);
    }

    @Test
    void validateAndBind_ownConfirmedReportMedia_isBoundToHost() {
        MediaObject media = uploaded("REPORT", reporter);
        when(repository.findAllByPublicIdIn(anyCollection()))
                .thenReturn(List.of(media));

        api.validateAndBind("REPORT", reportId, reporter, List.of(media.getPublicId()));

        // The object is now bound to the filed report (the serve path's visibility anchor).
        assertThat(media.getOwnerId()).isEqualTo(reportId);
    }

    @Test
    void validateAndBind_emptyOrNull_isNoOp() {
        api.validateAndBind("REPORT", reportId, reporter, List.of());
        api.validateAndBind("REPORT", reportId, reporter, null);
        // No repository interaction, no throw — a report with no attachments is valid.
    }

    @Test
    void validateAndBind_anonymousFiling_withMedia_isRejected() {
        // PDPA guard: an anonymous report (null reporter) may not carry account-owned media.
        assertThatThrownBy(() -> api.validateAndBind("REPORT", reportId, null, List.of(UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void validateAndBind_unknownId_isRejected() {
        UUID unknown = UUID.randomUUID();
        when(repository.findAllByPublicIdIn(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> api.validateAndBind("REPORT", reportId, reporter, List.of(unknown)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void validateAndBind_otherCitizensMedia_isRejected_andNotBound() {
        // THE OWNERSHIP GUARD: media uploaded by someone else must never attach to this citizen's report.
        UUID someoneElse = UUID.randomUUID();
        MediaObject media = uploaded("REPORT", someoneElse);
        when(repository.findAllByPublicIdIn(anyCollection())).thenReturn(List.of(media));

        assertThatThrownBy(() -> api.validateAndBind("REPORT", reportId, reporter, List.of(media.getPublicId())))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(media.getOwnerId()).isNull();
    }

    @Test
    void validateAndBind_wrongOwnerType_isRejected() {
        // A PROFILE_AVATAR cannot be attached as a REPORT photo.
        MediaObject media = uploaded("PROFILE_AVATAR", reporter);
        when(repository.findAllByPublicIdIn(anyCollection())).thenReturn(List.of(media));

        assertThatThrownBy(() -> api.validateAndBind("REPORT", reportId, reporter, List.of(media.getPublicId())))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void validateAndBind_notConfirmedUploaded_isRejected() {
        // THE CONFIRM GUARD: a dangling upload-intent (never confirmed) is not attachable.
        MediaObject media = pending("REPORT", reporter); // not markUploaded()
        when(repository.findAllByPublicIdIn(anyCollection())).thenReturn(List.of(media));

        assertThatThrownBy(() -> api.validateAndBind("REPORT", reportId, reporter, List.of(media.getPublicId())))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(media.getOwnerId()).isNull();
    }

    @Test
    void validateAndBind_alreadyBoundToAnotherHost_isRejected() {
        // Single-bind guard: an object already bound to a different report cannot be grafted onto this one.
        MediaObject media = uploaded("REPORT", reporter);
        media.bindTo(UUID.randomUUID()); // bound elsewhere
        when(repository.findAllByPublicIdIn(anyCollection())).thenReturn(List.of(media));

        assertThatThrownBy(() -> api.validateAndBind("REPORT", reportId, reporter, List.of(media.getPublicId())))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void validateAndBind_rebindToSameHost_isIdempotent() {
        // Re-filing with the same already-bound-to-THIS-host object is a no-op, not an error.
        MediaObject media = uploaded("REPORT", reporter);
        media.bindTo(reportId);
        when(repository.findAllByPublicIdIn(anyCollection())).thenReturn(List.of(media));

        api.validateAndBind("REPORT", reportId, reporter, List.of(media.getPublicId()));

        assertThat(media.getOwnerId()).isEqualTo(reportId);
    }

    @Test
    void attachmentsOf_returnsBoundPublicIds() {
        MediaObject a = uploaded("REPORT", reporter);
        a.bindTo(reportId);
        when(repository.findByOwnerTypeAndOwnerId("REPORT", reportId)).thenReturn(List.of(a));

        List<UUID> ids = api.attachmentsOf("REPORT", reportId);

        assertThat(ids).containsExactly(a.getPublicId());
    }

    // ---------------------------------------------------------------------------------------------

    /** A confirmed-uploaded, unbound media object of the given type uploaded by the given profile. */
    private MediaObject uploaded(String ownerType, UUID uploader) {
        MediaObject m = pending(ownerType, uploader);
        m.markUploaded();
        return m;
    }

    /** A PENDING, unbound, not-yet-confirmed media object (publicId assigned). */
    private MediaObject pending(String ownerType, UUID uploader) {
        MediaObject m = new MediaObject(ownerType, null, "quarantine/2026/06/" + UUID.randomUUID(),
                "f.jpg", "image/jpeg", 1024L, uploader);
        assignPublicId(m, UUID.randomUUID());
        return m;
    }

    /** Sets the inherited {@code publicId} on a transient entity for unit tests (no EntityManager). */
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
