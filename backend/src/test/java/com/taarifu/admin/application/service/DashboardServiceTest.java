package com.taarifu.admin.application.service;

import com.taarifu.admin.api.dto.DashboardSectionDto;
import com.taarifu.admin.api.dto.DashboardStatsDto;
import com.taarifu.admin.api.spi.ModuleStat;
import com.taarifu.admin.api.spi.ModuleStatsProvider;
import com.taarifu.common.domain.port.ClockPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DashboardService} — the cross-module aggregation + fault-isolation rules (M14,
 * UC-H06; ARCHITECTURE §1 "degrade gracefully").
 *
 * <p>Responsibility: proves (a) every provider's stats are aggregated and ordered stably, (b) a single
 * throwing provider is isolated as a {@code degraded} section while the others still render — so one
 * module can never blank the whole dashboard — and (c) a contract-violating {@code null} from a provider
 * is also degraded, never an NPE. Each test fails if the isolation were removed (CLAUDE.md §10). No Docker.</p>
 */
class DashboardServiceTest {

    /** A fixed clock so the {@code generatedAt} stamp is deterministic. */
    private final ClockPort clock = () -> Instant.parse("2026-06-23T09:00:00Z");

    /** A simple provider returning canned stats. */
    private static ModuleStatsProvider provider(String section, ModuleStat... stats) {
        return new ModuleStatsProvider() {
            @Override
            public String section() {
                return section;
            }

            @Override
            public List<ModuleStat> stats() {
                return List.of(stats);
            }
        };
    }

    @Test
    void aggregatesAllProviders_orderedBySection() {
        var reporting = provider("reporting", new ModuleStat("reporting.reports.OPEN", "Open reports", 12));
        var identity = provider("identity", new ModuleStat("identity.users.tier.T3", "Verified users", 5));

        // Pass them out of order to prove the service sorts sections stably for the UI.
        DashboardStatsDto result = new DashboardService(List.of(reporting, identity), clock).compute();

        assertThat(result.sections()).extracting(DashboardSectionDto::section)
                .containsExactly("identity", "reporting");
        assertThat(result.sections()).noneMatch(DashboardSectionDto::degraded);
        assertThat(result.generatedAt()).isEqualTo(Instant.parse("2026-06-23T09:00:00Z"));
    }

    @Test
    void emptyProviderSet_yieldsEmptyDashboard_notFailure() {
        // Before any module publishes a provider, the dashboard renders empty rather than erroring.
        DashboardStatsDto result = new DashboardService(List.of(), clock).compute();
        assertThat(result.sections()).isEmpty();
        assertThat(result.generatedAt()).isEqualTo(Instant.parse("2026-06-23T09:00:00Z"));
    }

    @Test
    void throwingProvider_isIsolatedAsDegraded_othersStillRender() {
        var healthy = provider("reporting", new ModuleStat("reporting.reports.OPEN", "Open reports", 3));
        ModuleStatsProvider broken = new ModuleStatsProvider() {
            @Override
            public String section() {
                return "moderation";
            }

            @Override
            public List<ModuleStat> stats() {
                throw new IllegalStateException("provider DB blew up");
            }
        };

        DashboardStatsDto result = new DashboardService(List.of(healthy, broken), clock).compute();

        // The broken section is reported degraded with no stats; the healthy one is intact.
        DashboardSectionDto moderation = result.sections().stream()
                .filter(s -> s.section().equals("moderation")).findFirst().orElseThrow();
        assertThat(moderation.degraded()).isTrue();
        assertThat(moderation.stats()).isEmpty();

        DashboardSectionDto reporting = result.sections().stream()
                .filter(s -> s.section().equals("reporting")).findFirst().orElseThrow();
        assertThat(reporting.degraded()).isFalse();
        assertThat(reporting.stats()).hasSize(1);
    }

    @Test
    void nullStats_fromMisbehavingProvider_isDegraded_notNpe() {
        ModuleStatsProvider nullProvider = new ModuleStatsProvider() {
            @Override
            public String section() {
                return "tokens";
            }

            @Override
            public List<ModuleStat> stats() {
                return null; // contract violation — must be tolerated as degraded
            }
        };

        DashboardStatsDto result = new DashboardService(List.of(nullProvider), clock).compute();

        assertThat(result.sections()).singleElement()
                .satisfies(s -> {
                    assertThat(s.section()).isEqualTo("tokens");
                    assertThat(s.degraded()).isTrue();
                    assertThat(s.stats()).isEmpty();
                });
    }

    @Test
    void moduleStat_rejectsNegativeValue() {
        // The count invariant is enforced at construction so a negative can never reach a dashboard tile.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ModuleStat("k", "label", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
