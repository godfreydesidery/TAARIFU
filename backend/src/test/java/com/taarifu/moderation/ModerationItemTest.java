package com.taarifu.moderation;

import com.taarifu.moderation.domain.model.ModerationItem;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.domain.model.enums.ModerationItemStatus;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ModerationItem} domain behaviour — SLA stamping, severity escalation, lifecycle.
 *
 * <p>These are the §25.8 queue mechanics: the review deadline derives from severity, and a later
 * higher-severity flag tightens both severity and deadline (queue prioritisation). No Spring/Docker.</p>
 */
class ModerationItemTest {

    private static final Instant T0 = Instant.parse("2026-06-23T09:00:00Z");

    @Test
    void slaDeadlineIsStampedFromSeverityOnOpen() {
        ModerationItem item = ModerationItem.open(FlagSubjectType.COMMENT, UUID.randomUUID(),
                null, ModerationSeverity.LOW, T0);
        // LOW → ≤72h.
        assertThat(item.getSlaDueAt()).isEqualTo(T0.plus(ModerationSeverity.LOW.reviewTarget()));
        assertThat(item.getStatus()).isEqualTo(ModerationItemStatus.PENDING);
        assertThat(item.getFlagCount()).isZero();
    }

    @Test
    void recordFlagEscalatesSeverityAndTightensSla() {
        ModerationItem item = ModerationItem.open(FlagSubjectType.COMMENT, UUID.randomUUID(),
                null, ModerationSeverity.LOW, T0);
        Instant later = T0.plusSeconds(60);

        // A CRITICAL flag arrives: severity escalates and the deadline is re-stamped tighter.
        item.recordFlag(ModerationSeverity.CRITICAL, later);

        assertThat(item.getSeverity()).isEqualTo(ModerationSeverity.CRITICAL);
        assertThat(item.getSlaDueAt()).isEqualTo(later.plus(ModerationSeverity.CRITICAL.reviewTarget()));
        assertThat(item.getFlagCount()).isEqualTo(1);
    }

    @Test
    void recordFlagNeverDowngradesSeverity() {
        ModerationItem item = ModerationItem.open(FlagSubjectType.COMMENT, UUID.randomUUID(),
                null, ModerationSeverity.CRITICAL, T0);
        Instant later = T0.plusSeconds(60);

        item.recordFlag(ModerationSeverity.LOW, later);

        // Still CRITICAL; the SLA is not loosened by a lower-severity flag.
        assertThat(item.getSeverity()).isEqualTo(ModerationSeverity.CRITICAL);
        assertThat(item.getSlaDueAt()).isEqualTo(T0.plus(ModerationSeverity.CRITICAL.reviewTarget()));
        assertThat(item.getFlagCount()).isEqualTo(1);
    }

    @Test
    void claimAndCloseDriveLifecycle() {
        ModerationItem item = ModerationItem.open(FlagSubjectType.REPORT, UUID.randomUUID(),
                null, ModerationSeverity.MEDIUM, T0);
        UUID moderator = UUID.randomUUID();

        item.claim(moderator);
        assertThat(item.getStatus()).isEqualTo(ModerationItemStatus.IN_REVIEW);
        assertThat(item.getAssignedModeratorId()).isEqualTo(moderator);
        assertThat(item.isTerminal()).isFalse();

        Instant closeAt = T0.plusSeconds(3600);
        item.close(ModerationItemStatus.ACTIONED, closeAt);
        assertThat(item.isTerminal()).isTrue();
        assertThat(item.getClosedAt()).isEqualTo(closeAt);
    }
}
