package com.taarifu.moderation;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.dto.AutoAssistResultDto;
import com.taarifu.moderation.api.event.ModerationEventTypes;
import com.taarifu.moderation.application.service.AutoAssistService;
import com.taarifu.moderation.application.service.SeverityPolicy;
import com.taarifu.moderation.domain.model.ModerationItem;
import com.taarifu.moderation.domain.model.enums.ContentSignal;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;
import com.taarifu.moderation.domain.port.ContentSafety;
import com.taarifu.moderation.domain.port.ContentSafety.ContentSafetyResult;
import com.taarifu.moderation.domain.port.ContentSafety.SafetySignal;
import com.taarifu.moderation.domain.repository.ModerationItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AutoAssistService} — the hold-and-prioritise auto-assist pipeline (US-12.3, UC-H05,
 * D-Q8, R21; ADR-0018). No Spring/Docker (mocked port + repos).
 *
 * <p>The load-bearing guarantees proved here:</p>
 * <ul>
 *   <li><b>Never auto-removes (R21):</b> {@link AutoAssistService} has no {@code ModerationAction}
 *       dependency at all — a held item is opened/marked, never actioned/closed. The compile-time absence is
 *       the real guarantee; these tests assert it holds (opens a PENDING auto-assisted item) and closes
 *       nothing.</li>
 *   <li><b>Conservative threshold (R21):</b> a signal below the hold threshold holds nothing.</li>
 *   <li><b>Degradation (EI-18):</b> an empty scorer result holds nothing — content flows to humans.</li>
 *   <li><b>Analytics (Appendix E):</b> every triage emits an {@code auto_moderation_triaged} fact carrying
 *       ids/codes only — no content, no author identity.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AutoAssistServiceTest {

    @Mock private ContentSafety contentSafety;
    @Mock private ModerationItemRepository itemRepository;
    @Mock private SeverityPolicy severityPolicy;
    @Mock private ClockPort clock;
    @Mock private OutboxWriter outboxWriter;

    private AutoAssistService service;

    private static final Instant NOW = Instant.parse("2026-06-25T10:00:00Z");
    private static final double THRESHOLD = 0.80;
    private final UUID subjectId = UUID.randomUUID();
    private final UUID author = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AutoAssistService(contentSafety, itemRepository, severityPolicy, clock,
                outboxWriter, THRESHOLD);
    }

    private AutoAssistResultDto triage() {
        return service.triage(FlagSubjectType.COMMENT, subjectId, author, "some text", "sw");
    }

    @Test
    void holdsAndMarksItem_whenSignalAtOrAboveThreshold_butNeverActions() {
        when(contentSafety.score(any())).thenReturn(new ContentSafetyResult(
                List.of(new SafetySignal(ContentSignal.PROFANITY, 0.85)), true));
        when(severityPolicy.severityForSignal(ContentSignal.PROFANITY)).thenReturn(ModerationSeverity.MEDIUM);
        when(itemRepository.findBySubjectTypeAndSubjectId(FlagSubjectType.COMMENT, subjectId))
                .thenReturn(Optional.empty());
        when(clock.now()).thenReturn(NOW);
        when(itemRepository.save(any(ModerationItem.class))).thenAnswer(inv -> inv.getArgument(0));

        AutoAssistResultDto result = triage();

        assertThat(result.held()).isTrue();
        assertThat(result.topSignal()).isEqualTo(ContentSignal.PROFANITY);

        ArgumentCaptor<ModerationItem> captor = ArgumentCaptor.forClass(ModerationItem.class);
        verify(itemRepository).save(captor.capture());
        ModerationItem held = captor.getValue();
        assertThat(held.isAutoAssisted()).isTrue();
        assertThat(held.getAutoSignal()).isEqualTo(ContentSignal.PROFANITY);
        // NEVER auto-removed: the item is held for a HUMAN — it is NOT terminal/closed (assist only, R21).
        assertThat(held.isTerminal()).isFalse();
        assertThat(held.getClosedAt()).isNull();
    }

    @Test
    void holdsNothing_whenBelowThreshold_humanPipelineIsTheFloor() {
        when(contentSafety.score(any())).thenReturn(new ContentSafetyResult(
                List.of(new SafetySignal(ContentSignal.PROFANITY, 0.50)), false));
        when(clock.now()).thenReturn(NOW);

        AutoAssistResultDto result = triage();

        assertThat(result.held()).isFalse();
        // No queue item opened/escalated when below the conservative threshold.
        verify(itemRepository, never()).save(any());
    }

    @Test
    void holdsNothing_whenScorerReturnsEmpty_degradationRoutesToHumans() {
        when(contentSafety.score(any())).thenReturn(ContentSafetyResult.empty());
        when(clock.now()).thenReturn(NOW);

        assertThat(triage().held()).isFalse();
        verify(itemRepository, never()).save(any());
    }

    @Test
    void emitsAutoModerationTriagedFact_idsAndCodesOnly_noPii() {
        when(contentSafety.score(any())).thenReturn(new ContentSafetyResult(
                List.of(new SafetySignal(ContentSignal.PII, 0.90)), true));
        when(severityPolicy.severityForSignal(ContentSignal.PII)).thenReturn(ModerationSeverity.HIGH);
        when(itemRepository.findBySubjectTypeAndSubjectId(FlagSubjectType.COMMENT, subjectId))
                .thenReturn(Optional.empty());
        when(clock.now()).thenReturn(NOW);
        when(itemRepository.save(any(ModerationItem.class))).thenAnswer(inv -> inv.getArgument(0));

        triage();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventEnvelope<?>> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outboxWriter).append(envelope.capture());
        assertThat(envelope.getValue().eventType()).isEqualTo(AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED);
        assertThat(envelope.getValue().aggregateId()).isEqualTo(subjectId);
        CivicActivityRecorded fact = (CivicActivityRecorded) envelope.getValue().payload();
        assertThat(fact.analyticsEventType()).isEqualTo(ModerationEventTypes.AUTO_MODERATION_TRIAGED);
        assertThat(fact.outcome()).isEqualTo(ContentSignal.PII.name()); // top signal, controlled vocab
        assertThat(fact.breachType()).isEqualTo("HELD");
        assertThat(fact.actorRef()).isNull();  // no actor identity
        assertThat(fact.activeRole()).isEqualTo("MODERATOR");
    }
}
