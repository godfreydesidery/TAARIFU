package com.taarifu.moderation;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.dto.FlagContentRequest;
import com.taarifu.moderation.application.service.FlagService;
import com.taarifu.moderation.application.service.SeverityPolicy;
import com.taarifu.moderation.application.service.SubjectAuthorResolver;
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
    @Mock private ClockPort clock;

    private FlagService service;

    private static final Instant NOW = Instant.parse("2026-06-23T10:00:00Z");
    private final UUID flagger = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new FlagService(flagRepository, itemRepository, severityPolicy, subjectAuthorResolver, clock);
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
        when(clock.now()).thenReturn(NOW);
        when(itemRepository.save(any(ModerationItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(flagRepository.save(any(Flag.class))).thenAnswer(inv -> inv.getArgument(0));

        service.flag(flagger, request());

        ArgumentCaptor<ModerationItem> captor = ArgumentCaptor.forClass(ModerationItem.class);
        org.mockito.Mockito.verify(itemRepository).save(captor.capture());
        assertThat(captor.getValue().getSubjectAuthorProfileId()).isEqualTo(author);
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
        when(clock.now()).thenReturn(NOW);
        when(itemRepository.save(any(ModerationItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(flagRepository.save(any(Flag.class))).thenAnswer(inv -> inv.getArgument(0));

        service.flag(flagger, request());

        ArgumentCaptor<ModerationItem> captor = ArgumentCaptor.forClass(ModerationItem.class);
        org.mockito.Mockito.verify(itemRepository).save(captor.capture());
        assertThat(captor.getValue().getSubjectAuthorProfileId()).isNull();
    }
}
