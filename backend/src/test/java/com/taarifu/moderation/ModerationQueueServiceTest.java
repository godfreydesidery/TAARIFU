package com.taarifu.moderation;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.moderation.api.dto.TakeActionRequest;
import com.taarifu.moderation.application.service.ModerationQueueService;
import com.taarifu.moderation.domain.model.ModerationAction;
import com.taarifu.moderation.domain.model.ModerationItem;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.domain.model.enums.ModerationActionType;
import com.taarifu.moderation.domain.model.enums.ModerationItemStatus;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;
import com.taarifu.moderation.domain.repository.FlagRepository;
import com.taarifu.moderation.domain.repository.ModerationActionRepository;
import com.taarifu.moderation.domain.repository.ModerationItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ModerationQueueService} — the conflict-of-interest keystone (D16, §25.8).
 *
 * <p>Responsibility: proves a moderator <b>cannot action a queue item whose subject they authored</b>
 * (CONFLICT_OF_INTEREST + audited), and that a clean (non-self) action is recorded append-only and closes
 * the item. The self-action test is the regression that <b>fails if the D16 guard is removed</b>. No
 * Spring context / Docker — repositories, the {@code ScopeGuard}, audit and clock are mocked.</p>
 */
@ExtendWith(MockitoExtension.class)
class ModerationQueueServiceTest {

    @Mock private ModerationItemRepository itemRepository;
    @Mock private ModerationActionRepository actionRepository;
    @Mock private FlagRepository flagRepository;
    @Mock private ScopeGuard scopeGuard;
    @Mock private AuditEventService audit;
    @Mock private ClockPort clock;
    @Mock private OutboxWriter outboxWriter;

    private ModerationQueueService service;

    private static final Instant NOW = Instant.parse("2026-06-23T10:00:00Z");
    private final UUID moderator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ModerationQueueService(itemRepository, actionRepository, flagRepository,
                scopeGuard, audit, clock, outboxWriter);
    }

    /** Builds a PENDING item authored by {@code authorId}, with a stamped publicId for DTO mapping. */
    private ModerationItem itemAuthoredBy(UUID authorId) {
        ModerationItem item = ModerationItem.open(FlagSubjectType.COMMENT, UUID.randomUUID(),
                authorId, ModerationSeverity.MEDIUM, NOW);
        setPublicId(item, UUID.randomUUID());
        return item;
    }

    @Test
    void blocksAndAuditsWhenModeratorActionsOwnContent() {
        // The item's author IS the acting moderator → D16 conflict.
        ModerationItem ownItem = itemAuthoredBy(moderator);
        UUID itemId = ownItem.getPublicId();
        when(itemRepository.findByPublicId(itemId)).thenReturn(Optional.of(ownItem));
        // isNotSelf(author) == false because author == caller (the guard's contract).
        when(scopeGuard.isNotSelf(moderator)).thenReturn(false);

        TakeActionRequest req = new TakeActionRequest(ModerationActionType.REMOVE, "RULE_ABUSE", null);

        assertThatThrownBy(() -> service.takeAction(moderator, itemId, req))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        // No action persisted; a self-action-blocked audit event was written.
        verify(actionRepository, never()).save(any());
        verify(audit).record(any(AuditEvent.class));
    }

    @Test
    void recordsActionAndClosesItemForNonSelfModerator() {
        UUID author = UUID.randomUUID(); // a different person from the moderator
        ModerationItem item = itemAuthoredBy(author);
        UUID itemId = item.getPublicId();
        when(itemRepository.findByPublicId(itemId)).thenReturn(Optional.of(item));
        when(scopeGuard.isNotSelf(author)).thenReturn(true);
        when(clock.now()).thenReturn(NOW);
        when(flagRepository.findBySubjectTypeAndSubjectId(any(), any())).thenReturn(List.of());
        when(actionRepository.save(any(ModerationAction.class)))
                .thenAnswer(inv -> {
                    ModerationAction a = inv.getArgument(0);
                    setPublicId(a, UUID.randomUUID());
                    return a;
                });

        TakeActionRequest req = new TakeActionRequest(ModerationActionType.REMOVE, "RULE_ABUSE", "note");
        var dto = service.takeAction(moderator, itemId, req);

        assertThat(dto).isNotNull();
        assertThat(dto.type()).isEqualTo(ModerationActionType.REMOVE);
        // REMOVE is a sanctioning action → item ACTIONED.
        assertThat(item.getStatus()).isEqualTo(ModerationItemStatus.ACTIONED);
        verify(actionRepository).save(any(ModerationAction.class));

        // ANALYTICS (Appendix E, M15): a moderation_action_taken civic-activity fact is appended to the outbox
        // with the correct dimensions — eventType=MODERATION_ACTION_TAKEN, activeRole=MODERATOR, outcome=the
        // action taken (REMOVE), and NO PII (no author id, no content). This assertion fails if the emit is
        // dropped or carries the wrong dimensions.
        ArgumentCaptor<EventEnvelope<?>> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outboxWriter).append(envelope.capture());
        assertThat(envelope.getValue().eventType()).isEqualTo(AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED);
        assertThat(envelope.getValue().payload()).isInstanceOf(CivicActivityRecorded.class);
        CivicActivityRecorded fact = (CivicActivityRecorded) envelope.getValue().payload();
        assertThat(fact.analyticsEventType()).isEqualTo(AnalyticsEventTypes.MODERATION_ACTION_TAKEN);
        assertThat(fact.activeRole()).isEqualTo("MODERATOR");
        assertThat(fact.outcome()).isEqualTo(ModerationActionType.REMOVE.name());
        assertThat(fact.actorRef()).isNull(); // no PII on the analytics fact
    }

    @Test
    void approveDismissesTheItem() {
        UUID author = UUID.randomUUID();
        ModerationItem item = itemAuthoredBy(author);
        UUID itemId = item.getPublicId();
        when(itemRepository.findByPublicId(itemId)).thenReturn(Optional.of(item));
        when(scopeGuard.isNotSelf(author)).thenReturn(true);
        when(clock.now()).thenReturn(NOW);
        when(flagRepository.findBySubjectTypeAndSubjectId(any(), any())).thenReturn(List.of());
        when(actionRepository.save(any(ModerationAction.class)))
                .thenAnswer(inv -> {
                    ModerationAction a = inv.getArgument(0);
                    setPublicId(a, UUID.randomUUID());
                    return a;
                });

        TakeActionRequest req = new TakeActionRequest(ModerationActionType.APPROVE, "NO_VIOLATION", null);
        service.takeAction(moderator, itemId, req);

        assertThat(item.getStatus()).isEqualTo(ModerationItemStatus.DISMISSED);
    }

    @Test
    void rejectsActionOnUnknownItem() {
        UUID itemId = UUID.randomUUID();
        when(itemRepository.findByPublicId(itemId)).thenReturn(Optional.empty());
        TakeActionRequest req = new TakeActionRequest(ModerationActionType.HIDE, "X", null);

        assertThatThrownBy(() -> service.takeAction(moderator, itemId, req))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    /** Sets BaseEntity.publicId via reflection (assigned on persist in production; needed for unit DTOs). */
    private static void setPublicId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("publicId");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
