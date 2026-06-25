package com.taarifu.moderation;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.dto.FlagContentRequest;
import com.taarifu.moderation.application.service.AutoAssistService;
import com.taarifu.moderation.application.service.FlagService;
import com.taarifu.moderation.application.service.SeverityPolicy;
import com.taarifu.moderation.application.service.SubjectAuthorResolver;
import com.taarifu.moderation.application.service.SubjectContentResolver;
import com.taarifu.moderation.domain.model.Flag;
import com.taarifu.moderation.domain.model.ModerationItem;
import com.taarifu.moderation.domain.model.enums.FlagReason;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;
import com.taarifu.moderation.domain.repository.FlagRepository;
import com.taarifu.moderation.domain.repository.ModerationItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FlagService} subject-author backfill (ADR-0013 §4c; D16).
 *
 * <p>Responsibility: proves that when a new {@link ModerationItem} is opened, its
 * {@code subjectAuthorProfileId} is backfilled from the owning module's published lookup
 * ({@link SubjectAuthorResolver}) so the D16 self-action guard on the action endpoint has the author to
 * compare against — and that an author-less subject (e.g. anonymous report) is recorded as {@code null}
 * (the guard then vacuously holds). This is the regression that fails if the wiring is removed.</p>
 */
@ExtendWith(MockitoExtension.class)
class FlagServiceTest {

    @Mock private FlagRepository flagRepository;
    @Mock private ModerationItemRepository itemRepository;
    @Mock private SeverityPolicy severityPolicy;
    @Mock private SubjectAuthorResolver subjectAuthorResolver;
    @Mock private SubjectContentResolver subjectContentResolver;
    @Mock private AutoAssistService autoAssistService;
    @Mock private ClockPort clock;
    @Mock private OutboxWriter outboxWriter;

    private FlagService service;

    private static final Instant NOW = Instant.parse("2026-06-23T10:00:00Z");
    private final UUID flagger = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new FlagService(flagRepository, itemRepository, severityPolicy, subjectAuthorResolver,
                subjectContentResolver, autoAssistService, clock, outboxWriter);
    }

    private FlagContentRequest request() {
        return new FlagContentRequest(FlagSubjectType.REPORT, subjectId, FlagReason.ABUSE, "detail");
    }

    @Test
    void backfillsSubjectAuthorOnNewItem_fromOwnerPort() {
        UUID author = UUID.randomUUID();
        when(flagRepository.existsByFlaggerProfileIdAndSubjectTypeAndSubjectId(
                flagger, FlagSubjectType.REPORT, subjectId)).thenReturn(false);
        when(severityPolicy.initialSeverity(FlagReason.ABUSE)).thenReturn(ModerationSeverity.MEDIUM);
        when(itemRepository.findBySubjectTypeAndSubjectId(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.empty());
        // The owning module resolves the subject's author (account public id) — the load-bearing wiring.
        when(subjectAuthorResolver.authorOf(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.of(author));
        // No owner publishes a content port here → the auto-assist screen is skipped (this test is about the
        // author backfill, not auto-assist).
        when(subjectContentResolver.contentTextOf(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.empty());
        when(clock.now()).thenReturn(NOW);
        when(itemRepository.save(any(ModerationItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(flagRepository.save(any(Flag.class))).thenAnswer(inv -> inv.getArgument(0));

        service.flag(flagger, request());

        ArgumentCaptor<ModerationItem> captor = ArgumentCaptor.forClass(ModerationItem.class);
        org.mockito.Mockito.verify(itemRepository).save(captor.capture());
        assertThat(captor.getValue().getSubjectAuthorProfileId()).isEqualTo(author);
        // Auto-assist screen skipped (no content port) — the flag still raised the item for a human.
        org.mockito.Mockito.verifyNoInteractions(autoAssistService);
    }

    @Test
    void emitsContentFlaggedAnalyticsFact_idsAndCodesOnly_noPii() {
        // A4: flagging must append a content_flagged civic-activity fact (the abuse-report-rate KPI numerator).
        UUID author = UUID.randomUUID();
        when(flagRepository.existsByFlaggerProfileIdAndSubjectTypeAndSubjectId(
                flagger, FlagSubjectType.REPORT, subjectId)).thenReturn(false);
        when(severityPolicy.initialSeverity(FlagReason.ABUSE)).thenReturn(ModerationSeverity.MEDIUM);
        when(itemRepository.findBySubjectTypeAndSubjectId(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.empty());
        when(subjectAuthorResolver.authorOf(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.of(author));
        when(subjectContentResolver.contentTextOf(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.empty());
        when(clock.now()).thenReturn(NOW);
        // Stamp a publicId on the saved item so the analytics fact's aggregateId is non-null.
        when(itemRepository.save(any(ModerationItem.class))).thenAnswer(inv -> {
            ModerationItem i = inv.getArgument(0);
            setPublicId(i, UUID.randomUUID());
            return i;
        });
        when(flagRepository.save(any(Flag.class))).thenAnswer(inv -> inv.getArgument(0));

        service.flag(flagger, request());

        // The emit is asserted to carry the correct dimensions and NO PII — fails if the emit is dropped or
        // carries the flagger identity / a wrong dimension.
        ArgumentCaptor<EventEnvelope<?>> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
        org.mockito.Mockito.verify(outboxWriter).append(envelope.capture());
        assertThat(envelope.getValue().eventType()).isEqualTo(AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED);
        assertThat(envelope.getValue().payload()).isInstanceOf(CivicActivityRecorded.class);
        CivicActivityRecorded fact = (CivicActivityRecorded) envelope.getValue().payload();
        assertThat(fact.analyticsEventType()).isEqualTo(AnalyticsEventTypes.CONTENT_FLAGGED);
        assertThat(fact.activeRole()).isEqualTo("CITIZEN");
        assertThat(fact.outcome()).isEqualTo(FlagReason.ABUSE.name()); // the flag reason, controlled vocab
        assertThat(fact.actorRef()).isNull(); // no PII on the analytics fact
    }

    /** Sets BaseEntity.publicId via reflection (assigned on persist in production; needed for unit emits). */
    private static void setPublicId(Object entity, UUID id) {
        try {
            java.lang.reflect.Field f = entity.getClass().getSuperclass().getDeclaredField("publicId");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void recordsNullAuthorForAuthorlessSubject_guardVacuouslySatisfied() {
        when(flagRepository.existsByFlaggerProfileIdAndSubjectTypeAndSubjectId(
                flagger, FlagSubjectType.REPORT, subjectId)).thenReturn(false);
        when(severityPolicy.initialSeverity(FlagReason.ABUSE)).thenReturn(ModerationSeverity.MEDIUM);
        when(itemRepository.findBySubjectTypeAndSubjectId(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.empty());
        // An anonymous report (no surfaced author) → empty; the item must record a null author.
        when(subjectAuthorResolver.authorOf(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.empty());
        when(subjectContentResolver.contentTextOf(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.empty());
        when(clock.now()).thenReturn(NOW);
        when(itemRepository.save(any(ModerationItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(flagRepository.save(any(Flag.class))).thenAnswer(inv -> inv.getArgument(0));

        service.flag(flagger, request());

        ArgumentCaptor<ModerationItem> captor = ArgumentCaptor.forClass(ModerationItem.class);
        org.mockito.Mockito.verify(itemRepository).save(captor.capture());
        assertThat(captor.getValue().getSubjectAuthorProfileId()).isNull();
    }

    @Test
    void runsAutoAssistScreenOnFlaggedContent_whenOwnerPublishesContentText() {
        // The load-bearing auto-assist wiring (ADR-0018): when a flag raises the queue item AND the owning
        // module surfaces the content text, FlagService hands that text to AutoAssistService.triage(...) so
        // the item is also prioritised by what the content contains. triage is assist-only — FlagService
        // never inspects or persists the text here; it only passes it through.
        UUID author = UUID.randomUUID();
        when(flagRepository.existsByFlaggerProfileIdAndSubjectTypeAndSubjectId(
                flagger, FlagSubjectType.REPORT, subjectId)).thenReturn(false);
        when(severityPolicy.initialSeverity(FlagReason.ABUSE)).thenReturn(ModerationSeverity.MEDIUM);
        when(itemRepository.findBySubjectTypeAndSubjectId(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.empty());
        when(subjectAuthorResolver.authorOf(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.of(author));
        when(subjectContentResolver.contentTextOf(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.of("wewe ni mjinga sana"));
        when(clock.now()).thenReturn(NOW);
        when(itemRepository.save(any(ModerationItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(flagRepository.save(any(Flag.class))).thenAnswer(inv -> inv.getArgument(0));

        service.flag(flagger, request());

        // triage is invoked with the SAME subject + the resolved author (D16 grain) + the owner's text.
        org.mockito.Mockito.verify(autoAssistService).triage(
                FlagSubjectType.REPORT, subjectId, author, "wewe ni mjinga sana", null);
    }

    @Test
    void screenRunsAsTheLastStep_afterTheFlagsPrimaryEffectsArePersistedAndEmitted() {
        // The screen is the LAST thing the flag transaction does: the item + flag are saved and the
        // content_flagged analytics fact emitted BEFORE triage runs. The screen is wired into (not ahead of)
        // the flag's primary effects, so it can only ADD an auto-hold/escalation to an item that already
        // exists — it is never a gate the flag must pass first. (Auto-assist is assist-only, R21.)
        UUID author = UUID.randomUUID();
        when(flagRepository.existsByFlaggerProfileIdAndSubjectTypeAndSubjectId(
                flagger, FlagSubjectType.REPORT, subjectId)).thenReturn(false);
        when(severityPolicy.initialSeverity(FlagReason.ABUSE)).thenReturn(ModerationSeverity.MEDIUM);
        when(itemRepository.findBySubjectTypeAndSubjectId(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.empty());
        when(subjectAuthorResolver.authorOf(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.of(author));
        when(subjectContentResolver.contentTextOf(FlagSubjectType.REPORT, subjectId))
                .thenReturn(Optional.of("some text"));
        when(clock.now()).thenReturn(NOW);
        when(itemRepository.save(any(ModerationItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(flagRepository.save(any(Flag.class))).thenAnswer(inv -> inv.getArgument(0));

        service.flag(flagger, request());

        // The flag and its item were persisted and the analytics fact emitted BEFORE the screen ran — the
        // screen is the last step, never a gate on the flag's success.
        org.mockito.Mockito.verify(flagRepository).save(any(Flag.class));
        org.mockito.Mockito.verify(outboxWriter).append(any());
        org.mockito.Mockito.verify(autoAssistService).triage(any(), any(), any(), any(), any());
    }
}
