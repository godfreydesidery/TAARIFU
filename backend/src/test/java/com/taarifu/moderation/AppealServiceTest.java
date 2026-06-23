package com.taarifu.moderation;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.moderation.api.dto.DecideAppealRequest;
import com.taarifu.moderation.api.dto.FileAppealRequest;
import com.taarifu.moderation.application.service.AppealService;
import com.taarifu.moderation.domain.model.Appeal;
import com.taarifu.moderation.domain.model.ModerationAction;
import com.taarifu.moderation.domain.model.ModerationItem;
import com.taarifu.moderation.domain.model.enums.AppealStatus;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.domain.model.enums.ModerationActionType;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;
import com.taarifu.moderation.domain.repository.AppealRepository;
import com.taarifu.moderation.domain.repository.ModerationActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppealService} — appeal independence + affected-party-only (D16, §25.8).
 *
 * <p>Responsibility: proves the two §25.8 fairness invariants:</p>
 * <ul>
 *   <li>the moderator who took an action <b>cannot decide its appeal</b> (CONFLICT_OF_INTEREST + audit) —
 *       the regression that fails if the independence guard is removed;</li>
 *   <li>only the <b>affected content author</b> may file an appeal (FORBIDDEN otherwise);</li>
 *   <li>a different moderator can decide it (UPHELD/OVERTURNED).</li>
 * </ul>
 * No Spring context / Docker.
 */
@ExtendWith(MockitoExtension.class)
class AppealServiceTest {

    @Mock private AppealRepository appealRepository;
    @Mock private ModerationActionRepository actionRepository;
    @Mock private AuditEventService audit;
    @Mock private ClockPort clock;

    private AppealService service;

    private static final Instant NOW = Instant.parse("2026-06-23T11:00:00Z");
    private final UUID originalModerator = UUID.randomUUID();
    private final UUID author = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AppealService(appealRepository, actionRepository, audit, clock);
    }

    /** A persisted-looking action taken by {@code originalModerator} against {@code author}'s content. */
    private ModerationAction actionByOriginalModerator() {
        ModerationItem item = ModerationItem.open(FlagSubjectType.COMMENT, UUID.randomUUID(),
                author, ModerationSeverity.MEDIUM, NOW);
        setPublicId(item, UUID.randomUUID());
        ModerationAction action = ModerationAction.record(item, ModerationActionType.REMOVE,
                originalModerator, author, "RULE_ABUSE", null, NOW);
        setPublicId(action, UUID.randomUUID());
        return action;
    }

    private Appeal openAppealOn(ModerationAction action) {
        Appeal appeal = Appeal.open(action, author, "I disagree");
        setPublicId(appeal, UUID.randomUUID());
        return appeal;
    }

    // -------- file appeal --------

    @Test
    void onlyAffectedAuthorMayFileAppeal() {
        ModerationAction action = actionByOriginalModerator();
        when(actionRepository.findByPublicId(action.getPublicId())).thenReturn(Optional.of(action));
        UUID someoneElse = UUID.randomUUID();

        assertThatThrownBy(() -> service.fileAppeal(someoneElse, action.getPublicId(),
                new FileAppealRequest("let me in")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(appealRepository, never()).save(any());
    }

    @Test
    void affectedAuthorCanFileAppeal() {
        ModerationAction action = actionByOriginalModerator();
        when(actionRepository.findByPublicId(action.getPublicId())).thenReturn(Optional.of(action));
        when(appealRepository.existsByActionPublicId(action.getPublicId())).thenReturn(false);
        when(appealRepository.save(any(Appeal.class))).thenAnswer(inv -> {
            Appeal a = inv.getArgument(0);
            setPublicId(a, UUID.randomUUID());
            return a;
        });

        var dto = service.fileAppeal(author, action.getPublicId(), new FileAppealRequest("grounds"));

        assertThat(dto).isNotNull();
        assertThat(dto.status()).isEqualTo(AppealStatus.OPEN);
        verify(appealRepository).save(any(Appeal.class));
    }

    // -------- decide appeal (independence) --------

    @Test
    void originalModeratorCannotDecideOwnAppeal() {
        ModerationAction action = actionByOriginalModerator();
        Appeal appeal = openAppealOn(action);
        when(appealRepository.findByPublicId(appeal.getPublicId())).thenReturn(Optional.of(appeal));

        // The SAME moderator who took the action attempts to decide the appeal → independence breach.
        assertThatThrownBy(() -> service.decideAppeal(originalModerator, appeal.getPublicId(),
                new DecideAppealRequest(AppealStatus.UPHELD, "stands")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        assertThat(appeal.getStatus()).isEqualTo(AppealStatus.OPEN); // unchanged
        verify(audit).record(any(AuditEvent.class));
        verify(appealRepository, never()).save(any());
    }

    @Test
    void differentModeratorCanOverturnAppeal() {
        ModerationAction action = actionByOriginalModerator();
        Appeal appeal = openAppealOn(action);
        when(appealRepository.findByPublicId(appeal.getPublicId())).thenReturn(Optional.of(appeal));
        when(clock.now()).thenReturn(NOW);
        when(appealRepository.save(any(Appeal.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID independentModerator = UUID.randomUUID();
        var dto = service.decideAppeal(independentModerator, appeal.getPublicId(),
                new DecideAppealRequest(AppealStatus.OVERTURNED, "reversed"));

        assertThat(dto.status()).isEqualTo(AppealStatus.OVERTURNED);
        assertThat(appeal.getHandledByModeratorId()).isEqualTo(independentModerator);
        assertThat(appeal.getDecidedAt()).isEqualTo(NOW);
    }

    @Test
    void cannotDecideAnAlreadyDecidedAppeal() {
        ModerationAction action = actionByOriginalModerator();
        Appeal appeal = openAppealOn(action);
        appeal.decide(AppealStatus.UPHELD, UUID.randomUUID(), "already done", NOW);
        when(appealRepository.findByPublicId(appeal.getPublicId())).thenReturn(Optional.of(appeal));

        assertThatThrownBy(() -> service.decideAppeal(UUID.randomUUID(), appeal.getPublicId(),
                new DecideAppealRequest(AppealStatus.OVERTURNED, "again")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

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
