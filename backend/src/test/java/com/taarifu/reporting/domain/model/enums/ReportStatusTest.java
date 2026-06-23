package com.taarifu.reporting.domain.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link ReportStatus} state machine (PRD §12.1).
 *
 * <p>Responsibility: pins the §12.1 transition rules so an accidental edit to {@link
 * ReportStatus#allowedNext()} that opens an illegal edge (or closes a legal one) fails the build. This is
 * the integrity guard the service relies on — these tests are what make removing/weakening it visible.</p>
 */
class ReportStatusTest {

    @ParameterizedTest
    @CsvSource({
            // The core happy path (§12.1).
            "NEW,ASSIGNED",
            "ASSIGNED,IN_PROGRESS",
            "IN_PROGRESS,AWAITING_INFO",
            "AWAITING_INFO,IN_PROGRESS",
            "IN_PROGRESS,RESOLVED",
            "RESOLVED,CLOSED",
            "RESOLVED,REOPENED",
            "REOPENED,ASSIGNED",
            // Triage-out and escalation edges.
            "NEW,REJECTED",
            "NEW,DUPLICATE",
            "NEW,ESCALATED",
            "IN_PROGRESS,ESCALATED",
            "ESCALATED,RESOLVED"
    })
    void allowsLegalTransitions(ReportStatus from, ReportStatus to) {
        assertThat(from.canTransitionTo(to))
                .as("%s -> %s should be legal per §12.1", from, to)
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            // Cannot skip straight to CLOSED from intake or active work.
            "NEW,CLOSED",
            "NEW,IN_PROGRESS",
            "IN_PROGRESS,CLOSED",
            // Cannot resolve straight from NEW (must be worked first).
            "NEW,RESOLVED",
            // A citizen confirm/dispute only applies from RESOLVED, not from ASSIGNED.
            "ASSIGNED,CLOSED",
            "ASSIGNED,REOPENED",
            // RESOLVED cannot jump back into active work without a dispute.
            "RESOLVED,IN_PROGRESS"
    })
    void rejectsIllegalTransitions(ReportStatus from, ReportStatus to) {
        assertThat(from.canTransitionTo(to))
                .as("%s -> %s should be illegal per §12.1", from, to)
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ReportStatus.class, names = {"CLOSED", "REJECTED", "DUPLICATE"})
    void terminalStatesHaveNoSuccessors(ReportStatus terminal) {
        assertThat(terminal.allowedNext()).isEmpty();
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.isActive()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ReportStatus.class,
            names = {"NEW", "ASSIGNED", "IN_PROGRESS", "AWAITING_INFO", "RESOLVED", "REOPENED", "ESCALATED"})
    void nonTerminalStatesAreActive(ReportStatus active) {
        assertThat(active.isTerminal()).isFalse();
        assertThat(active.isActive()).isTrue();
    }

    @Test
    void escalatedStaysActiveAndCanStillResolve() {
        // §12.1: escalation flags the case but it stays active — resolution remains reachable.
        assertThat(ReportStatus.ESCALATED.isActive()).isTrue();
        assertThat(ReportStatus.ESCALATED.canTransitionTo(ReportStatus.RESOLVED)).isTrue();
    }
}
