package com.taarifu.accountability.application.service;

import com.taarifu.accountability.api.dto.CreateAttendanceDto;
import com.taarifu.accountability.api.dto.CreateContributionDto;
import com.taarifu.accountability.api.dto.CreatePromiseDto;
import com.taarifu.accountability.api.dto.UpdatePromiseStatusDto;
import com.taarifu.accountability.application.mapper.AccountabilityMapper;
import com.taarifu.accountability.domain.model.Attendance;
import com.taarifu.accountability.domain.model.Promise;
import com.taarifu.accountability.domain.model.PromiseStatusEntry;
import com.taarifu.accountability.domain.model.RepresentativeContribution;
import com.taarifu.accountability.domain.model.enums.ContributionType;
import com.taarifu.accountability.domain.model.enums.PromiseStatus;
import com.taarifu.accountability.domain.repository.AttendanceRepository;
import com.taarifu.accountability.domain.repository.PromiseRepository;
import com.taarifu.accountability.domain.repository.PromiseStatusEntryRepository;
import com.taarifu.accountability.domain.repository.RepresentativeContributionRepository;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.institutions.api.RepresentativeQueryApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CurationService} — proving the referenced-representative existence guard
 * (PRD §10 Epic M6; D-Q4; ADR-0013 cross-module-via-api).
 *
 * <p>Responsibility: each curated authoring path (contribution, attendance, promise) confirms the
 * referenced representative actually exists via institutions' published
 * {@link RepresentativeQueryApi#exists(UUID)} port <b>before</b> persisting. The keystone tests assert
 * that a real rep persists, and that a <b>phantom</b> rep is rejected with {@code NOT_FOUND} and nothing
 * is written — each would fail if the existence guard were removed (CLAUDE.md §10: test the invariant,
 * not the happy path). No Docker: the persistence and cross-module ports are mocked.</p>
 */
@ExtendWith(MockitoExtension.class)
class CurationServiceTest {

    @Mock
    private RepresentativeContributionRepository contributionRepository;
    @Mock
    private AttendanceRepository attendanceRepository;
    @Mock
    private PromiseRepository promiseRepository;
    @Mock
    private PromiseStatusEntryRepository promiseStatusEntryRepository;
    @Mock
    private RepresentativeQueryApi representativeQueryApi;

    private final AccountabilityMapper mapper = new AccountabilityMapper();

    private final UUID representativeId = UUID.randomUUID();

    private CurationService service() {
        return new CurationService(contributionRepository, attendanceRepository, promiseRepository,
                promiseStatusEntryRepository, mapper, representativeQueryApi);
    }

    private CreateContributionDto contributionRequest() {
        return new CreateContributionDto(representativeId, ContributionType.SPEECH, "Maji ya Singida",
                "Floor speech on rural water", LocalDate.of(2026, 3, 1), "2026-S1", null, null);
    }

    private CreateAttendanceDto attendanceRequest() {
        return new CreateAttendanceDto(representativeId, "2026-S1-D04", Boolean.TRUE);
    }

    private CreatePromiseDto promiseRequest() {
        return new CreatePromiseDto(representativeId, "Build a ward dispensary",
                LocalDate.of(2026, 2, 1), PromiseStatus.MADE, null, null);
    }

    // ---------------------------------------------------------------------------------------------
    // Happy path: a real representative → the record persists.
    // ---------------------------------------------------------------------------------------------

    @Test
    void createContribution_realRepresentative_persists() {
        when(representativeQueryApi.exists(representativeId)).thenReturn(true);
        when(contributionRepository.save(any(RepresentativeContribution.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service().createContribution(contributionRequest());

        verify(representativeQueryApi).exists(representativeId);
        verify(contributionRepository).save(any(RepresentativeContribution.class));
    }

    @Test
    void recordAttendance_realRepresentative_persists() {
        when(representativeQueryApi.exists(representativeId)).thenReturn(true);
        when(attendanceRepository.findByRepresentativeIdAndSessionRef(representativeId, "2026-S1-D04"))
                .thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        service().recordAttendance(attendanceRequest());

        verify(representativeQueryApi).exists(representativeId);
        verify(attendanceRepository).save(any(Attendance.class));
    }

    @Test
    void createPromise_realRepresentative_persists() {
        when(representativeQueryApi.exists(representativeId)).thenReturn(true);
        when(promiseRepository.save(any(Promise.class))).thenAnswer(inv -> inv.getArgument(0));

        service().createPromise(promiseRequest());

        verify(representativeQueryApi).exists(representativeId);
        verify(promiseRepository).save(any(Promise.class));
    }

    // ---------------------------------------------------------------------------------------------
    // US-6.3: the promise STATUS TIMELINE is opened on create and appended on each genuine transition.
    // These would FAIL if the timeline wiring were removed (the citizen would lose the provenance).
    // ---------------------------------------------------------------------------------------------

    @Test
    void createPromise_opensTimelineWithInitialStatus() {
        when(representativeQueryApi.exists(representativeId)).thenReturn(true);
        when(promiseRepository.save(any(Promise.class))).thenAnswer(inv -> inv.getArgument(0));

        // The request omits a status → Promise.create defaults it to MADE; the first timeline entry MUST
        // mirror the PERSISTED status (read back from the saved entity), never the raw null request.
        service().createPromise(promiseRequest());

        ArgumentCaptor<PromiseStatusEntry> entry = ArgumentCaptor.forClass(PromiseStatusEntry.class);
        verify(promiseStatusEntryRepository).save(entry.capture());
        assertThat(entry.getValue().getStatus()).isEqualTo(PromiseStatus.MADE);
    }

    @Test
    void updatePromiseStatus_onTransition_appendsTimelineEntry() {
        UUID promisePublicId = UUID.randomUUID();
        Promise promise = Promise.create(representativeId, "Build a ward dispensary",
                LocalDate.of(2026, 2, 1), PromiseStatus.MADE, null, null);
        when(promiseRepository.findByPublicId(promisePublicId)).thenReturn(Optional.of(promise));
        when(promiseRepository.save(any(Promise.class))).thenAnswer(inv -> inv.getArgument(0));

        // MADE -> KEPT is a genuine transition → exactly one timeline entry is appended, carrying the note.
        service().updatePromiseStatus(promisePublicId,
                new UpdatePromiseStatusDto(PromiseStatus.KEPT, "s3://evidence/dispensary.pdf", "Opened May 2026"));

        ArgumentCaptor<PromiseStatusEntry> entry = ArgumentCaptor.forClass(PromiseStatusEntry.class);
        verify(promiseStatusEntryRepository).save(entry.capture());
        assertThat(entry.getValue().getStatus()).isEqualTo(PromiseStatus.KEPT);
        assertThat(entry.getValue().getNote()).isEqualTo("Opened May 2026");
    }

    @Test
    void updatePromiseStatus_sameStatus_appendsNoTimelineEntry() {
        UUID promisePublicId = UUID.randomUUID();
        Promise promise = Promise.create(representativeId, "Build a ward dispensary",
                LocalDate.of(2026, 2, 1), PromiseStatus.IN_PROGRESS, null, null);
        when(promiseRepository.findByPublicId(promisePublicId)).thenReturn(Optional.of(promise));
        when(promiseRepository.save(any(Promise.class))).thenAnswer(inv -> inv.getArgument(0));

        // Re-stating the SAME status (e.g. attaching new evidence) is NOT a transition → no spurious
        // "moved to X" timeline row (the timeline is the provenance of MOVES, not of every edit).
        service().updatePromiseStatus(promisePublicId,
                new UpdatePromiseStatusDto(PromiseStatus.IN_PROGRESS, "s3://evidence/progress.pdf", null));

        verify(promiseStatusEntryRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------------
    // Integrity guard: a phantom representative → NOT_FOUND, and NOTHING is persisted. These would FAIL
    // (the record would be written, orphaned) if the existence guard were removed.
    // ---------------------------------------------------------------------------------------------

    @Test
    void createContribution_phantomRepresentative_rejectedNotFound_nothingPersisted() {
        when(representativeQueryApi.exists(representativeId)).thenReturn(false);

        assertThatThrownBy(() -> service().createContribution(contributionRequest()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(contributionRepository, never()).save(any());
    }

    @Test
    void recordAttendance_phantomRepresentative_rejectedNotFound_nothingPersisted() {
        when(representativeQueryApi.exists(representativeId)).thenReturn(false);

        assertThatThrownBy(() -> service().recordAttendance(attendanceRequest()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        // The existence guard runs BEFORE the duplicate-session lookup, so neither is consulted.
        verify(attendanceRepository, never()).findByRepresentativeIdAndSessionRef(any(), any());
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void createPromise_phantomRepresentative_rejectedNotFound_nothingPersisted() {
        when(representativeQueryApi.exists(representativeId)).thenReturn(false);

        assertThatThrownBy(() -> service().createPromise(promiseRequest()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(promiseRepository, never()).save(any());
    }
}
