package com.taarifu.moderation;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.moderation.api.dto.TransparencyReportDto;
import com.taarifu.moderation.application.service.TransparencyReportService;
import com.taarifu.moderation.domain.repository.AppealRepository;
import com.taarifu.moderation.domain.repository.FlagRepository;
import com.taarifu.moderation.domain.repository.ModerationActionRepository;
import com.taarifu.moderation.domain.repository.ModerationItemRepository;
import com.taarifu.moderation.domain.repository.projection.CountByKeyProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransparencyReportService} — the §25 PII-free transparency report (ADR-0018).
 * No Spring/Docker (mocked repos).
 *
 * <p>Proves window defaulting (a {@code null} window resolves to the last 30 days, deterministic via the
 * injected clock) and that the report assembles every breakdown the moderation tables provide — action mix,
 * appeal outcomes, flags-by-reason, and the auto-vs-manual split — with the headline totals.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransparencyReportServiceTest {

    @Mock private FlagRepository flagRepository;
    @Mock private ModerationActionRepository actionRepository;
    @Mock private AppealRepository appealRepository;
    @Mock private ModerationItemRepository itemRepository;
    @Mock private ClockPort clock;

    private TransparencyReportService service;

    private static final Instant NOW = Instant.parse("2026-06-25T00:00:00Z");

    @BeforeEach
    void setUp() {
        service = new TransparencyReportService(flagRepository, actionRepository, appealRepository,
                itemRepository, clock);
    }

    /** A trivial {@link CountByKeyProjection} for stubbing grouped results. */
    private static CountByKeyProjection row(String key, long count) {
        return new CountByKeyProjection() {
            @Override public String getKey() {
                return key;
            }
            @Override public long getCount() {
                return count;
            }
        };
    }

    @Test
    void defaultsToLast30Days_andAssemblesAllBreakdowns() {
        when(clock.now()).thenReturn(NOW);
        when(flagRepository.countInWindow(any(), any())).thenReturn(7L);
        when(actionRepository.countInWindow(any(), any())).thenReturn(4L);
        when(appealRepository.countInWindow(any(), any())).thenReturn(2L);
        when(actionRepository.countByTypeInWindow(any(), any()))
                .thenReturn(List.of(row("REMOVE", 3), row("HIDE", 1)));
        when(appealRepository.countByStatusInWindow(any(), any()))
                .thenReturn(List.of(row("UPHELD", 1), row("OVERTURNED", 1)));
        when(flagRepository.countByReasonInWindow(any(), any()))
                .thenReturn(List.of(row("ABUSE", 5), row("SPAM", 2)));
        when(itemRepository.countByAssistModeInWindow(any(), any()))
                .thenReturn(List.of(row("AUTO_ASSISTED", 3), row("MANUAL", 4)));

        TransparencyReportDto report = service.report(null, null);

        // Window defaulted: to=now, from=now-30d (deterministic via the injected clock).
        assertThat(report.to()).isEqualTo(NOW);
        assertThat(report.from()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        // Headline totals.
        assertThat(report.totalFlags()).isEqualTo(7L);
        assertThat(report.totalActions()).isEqualTo(4L);
        assertThat(report.totalAppeals()).isEqualTo(2L);
        // Breakdowns mapped through to the DTO, keyed on codes only.
        assertThat(report.actionsByType()).extracting(TransparencyReportDto.TransparencyCountDto::key)
                .containsExactlyInAnyOrder("REMOVE", "HIDE");
        assertThat(report.appealsByOutcome()).extracting(TransparencyReportDto.TransparencyCountDto::key)
                .containsExactlyInAnyOrder("UPHELD", "OVERTURNED");
        assertThat(report.flagsByReason()).hasSize(2);
        assertThat(report.itemsByAssistMode()).extracting(TransparencyReportDto.TransparencyCountDto::key)
                .containsExactlyInAnyOrder("AUTO_ASSISTED", "MANUAL");
    }

    @Test
    void honoursExplicitWindow() {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-15T00:00:00Z");
        when(flagRepository.countInWindow(any(), any())).thenReturn(0L);
        when(actionRepository.countInWindow(any(), any())).thenReturn(0L);
        when(appealRepository.countInWindow(any(), any())).thenReturn(0L);
        when(actionRepository.countByTypeInWindow(any(), any())).thenReturn(List.of());
        when(appealRepository.countByStatusInWindow(any(), any())).thenReturn(List.of());
        when(flagRepository.countByReasonInWindow(any(), any())).thenReturn(List.of());
        when(itemRepository.countByAssistModeInWindow(any(), any())).thenReturn(List.of());

        TransparencyReportDto report = service.report(from, to);

        assertThat(report.from()).isEqualTo(from);
        assertThat(report.to()).isEqualTo(to);
        // The explicit window is passed straight through to the repositories.
        ArgumentCaptor<Instant> fromCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCap = ArgumentCaptor.forClass(Instant.class);
        verify(flagRepository).countByReasonInWindow(fromCap.capture(), toCap.capture());
        assertThat(fromCap.getValue()).isEqualTo(from);
        assertThat(toCap.getValue()).isEqualTo(to);
    }
}
