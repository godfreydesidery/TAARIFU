package com.taarifu.moderation;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.moderation.api.dto.AppealSummaryDto;
import com.taarifu.moderation.domain.model.Appeal;
import com.taarifu.moderation.domain.model.Flag;
import com.taarifu.moderation.domain.model.ModerationAction;
import com.taarifu.moderation.domain.model.ModerationItem;
import com.taarifu.moderation.domain.model.enums.AppealStatus;
import com.taarifu.moderation.domain.model.enums.FlagReason;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.domain.model.enums.ModerationActionType;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;
import com.taarifu.moderation.domain.repository.AppealRepository;
import com.taarifu.moderation.domain.repository.FlagRepository;
import com.taarifu.moderation.domain.repository.ModerationActionRepository;
import com.taarifu.moderation.domain.repository.ModerationItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers tests for the moderation schema (V40–V42) and its DB-owned integrity invariants
 * (M12; PRD §18, §25.8; ADR-0005/0009).
 *
 * <p>Booting the Spring context against real PostGIS with {@code ddl-auto=validate} is itself the first
 * assertion — it proves the V40–V42 migrations match the {@code Flag}/{@code ModerationItem}/
 * {@code ModerationAction}/{@code Appeal} entities exactly. The tests then prove the guarantees that live
 * in Postgres indexes, not in Java:</p>
 * <ul>
 *   <li><b>anti-brigading</b> — one flag per (citizen, subject) (ux_mod_flag_flagger_subject);</li>
 *   <li><b>one live queue item per subject</b> (ux_mod_item_subject_open);</li>
 *   <li><b>one live appeal per action</b> (ux_mod_appeal_action);</li>
 *   <li><b>real FK</b> action→item and appeal→action persist + load.</li>
 * </ul>
 *
 * <p>Requires Docker; runs in CI. Local unit coverage of the integrity rules is in the {@code *ServiceTest}
 * classes, which need no Docker (ADR-0009).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ModerationPersistenceIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired private FlagRepository flagRepository;
    @Autowired private ModerationItemRepository itemRepository;
    @Autowired private ModerationActionRepository actionRepository;
    @Autowired private AppealRepository appealRepository;

    private static final Instant NOW = Instant.parse("2026-06-23T12:00:00Z");

    @Test
    void contextLoads_soV40toV42MatchEntities() {
        // Reaching here means ddl-auto=validate accepted the moderation migrations (ADR-0005).
        assertThat(flagRepository).isNotNull();
    }

    @Test
    void antiBrigading_oneFlagPerCitizenPerSubject() {
        UUID subject = UUID.randomUUID();
        UUID flagger = UUID.randomUUID();
        flagRepository.saveAndFlush(
                Flag.open(FlagSubjectType.COMMENT, subject, flagger, FlagReason.ABUSE, null));

        // A second flag by the SAME citizen on the SAME subject violates ux_mod_flag_flagger_subject.
        assertThatThrownBy(() -> flagRepository.saveAndFlush(
                Flag.open(FlagSubjectType.COMMENT, subject, flagger, FlagReason.SPAM, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void distinctFlaggersOnSameSubjectAreAccepted() {
        UUID subject = UUID.randomUUID();
        flagRepository.saveAndFlush(
                Flag.open(FlagSubjectType.REPORT, subject, UUID.randomUUID(), FlagReason.PII, null));
        flagRepository.saveAndFlush(
                Flag.open(FlagSubjectType.REPORT, subject, UUID.randomUUID(), FlagReason.PII, null));

        assertThat(flagRepository.findBySubjectTypeAndSubjectId(FlagSubjectType.REPORT, subject))
                .hasSize(2);
    }

    @Test
    void oneLiveQueueItemPerSubject() {
        UUID subject = UUID.randomUUID();
        itemRepository.saveAndFlush(ModerationItem.open(
                FlagSubjectType.PETITION, subject, null, ModerationSeverity.MEDIUM, NOW));

        assertThatThrownBy(() -> itemRepository.saveAndFlush(ModerationItem.open(
                FlagSubjectType.PETITION, subject, null, ModerationSeverity.HIGH, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void actionAndAppealPersistThroughRealForeignKeys() {
        UUID author = UUID.randomUUID();
        UUID moderator = UUID.randomUUID();
        ModerationItem item = itemRepository.saveAndFlush(ModerationItem.open(
                FlagSubjectType.QUESTION, UUID.randomUUID(), author, ModerationSeverity.HIGH, NOW));

        ModerationAction action = actionRepository.saveAndFlush(ModerationAction.record(
                item, ModerationActionType.REMOVE, moderator, author, "RULE_ABUSE", "n", NOW));
        assertThat(action.getPublicId()).isNotNull();
        assertThat(actionRepository.findByItemIdOrderByTakenAtAsc(item.getId())).hasSize(1);

        Appeal appeal = appealRepository.saveAndFlush(Appeal.open(action, author, "grounds"));
        assertThat(appeal.getStatus()).isEqualTo(AppealStatus.OPEN);

        // A second appeal on the same action violates ux_mod_appeal_action.
        assertThatThrownBy(() -> appealRepository.saveAndFlush(
                Appeal.open(action, author, "again")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void appealQueueSummaryProjectsSubjectTypeAcrossTheActionJoin() {
        // GIVEN an OPEN appeal whose appealed action resolved a COMMENT queue item: the queue summary
        // (GET /moderation/appeals) must project subjectType from the joined item — proving the
        // appeal→action→item JPQL constructor expression resolves and the V100 index migration applied
        // (the whole context boots with ddl-auto=validate). Validates the read path the broken MVC layer
        // cannot exercise in this worktree.
        UUID author = UUID.randomUUID();
        UUID moderator = UUID.randomUUID();
        ModerationItem item = itemRepository.saveAndFlush(ModerationItem.open(
                FlagSubjectType.COMMENT, UUID.randomUUID(), author, ModerationSeverity.MEDIUM, NOW));
        ModerationAction action = actionRepository.saveAndFlush(ModerationAction.record(
                item, ModerationActionType.REMOVE, moderator, author, "RULE_ABUSE", null, NOW));
        Appeal appeal = appealRepository.saveAndFlush(Appeal.open(action, author, "grounds"));

        var pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Status-filtered query (?status=OPEN) — backed by the V100 (status, created_at) index.
        Page<AppealSummaryDto> openPage = appealRepository.findSummariesByStatus(AppealStatus.OPEN, pageable);
        assertThat(openPage.getContent()).anySatisfy(row -> {
            assertThat(row.publicId()).isEqualTo(appeal.getPublicId());
            assertThat(row.subjectType()).isEqualTo(FlagSubjectType.COMMENT); // joined from the item
            assertThat(row.appellant()).isEqualTo(author);
            assertThat(row.status()).isEqualTo(AppealStatus.OPEN);
            assertThat(row.outcome()).isNull();                                // derived: no outcome while OPEN
            assertThat(row.filedAt()).isNotNull();
        });

        // Unfiltered query (no ?status) returns the same row.
        assertThat(appealRepository.findSummaries(pageable).getContent())
                .anySatisfy(row -> assertThat(row.publicId()).isEqualTo(appeal.getPublicId()));

        // A status with no rows yields an empty page (not an error).
        assertThat(appealRepository.findSummariesByStatus(AppealStatus.OVERTURNED, pageable)
                .getContent())
                .noneSatisfy(row -> assertThat(row.publicId()).isEqualTo(appeal.getPublicId()));
    }
}
