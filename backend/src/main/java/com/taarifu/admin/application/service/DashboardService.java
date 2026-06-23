package com.taarifu.admin.application.service;

import com.taarifu.admin.api.dto.DashboardSectionDto;
import com.taarifu.admin.api.dto.DashboardStatsDto;
import com.taarifu.admin.api.spi.ModuleStat;
import com.taarifu.admin.api.spi.ModuleStatsProvider;
import com.taarifu.common.domain.port.ClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Aggregates every module's headline counts into the platform dashboard (M14, US-14.1, UC-H06).
 *
 * <p>Responsibility: collect the {@link ModuleStatsProvider}s Spring discovered (one per contributing
 * module — reporting, identity, moderation, …), call each, and assemble a {@link DashboardStatsDto}. The
 * admin module never imports those modules' internals; it depends only on the published provider SPI
 * (ADR-0013 §1, ARCHITECTURE §3.2). Adding a module's tiles is purely additive — a new provider bean —
 * with no change here (open/closed, CLAUDE.md §3).</p>
 *
 * <p><b>Fault isolation (ARCHITECTURE §1 "degrade gracefully"):</b> a single slow or throwing provider
 * must not blank the whole dashboard. Each provider is invoked in its own try/catch; a failure is logged
 * and that section is returned {@code degraded=true} with no stats, while every other section still
 * renders. This is why aggregation lives in a service (not inline in the controller) — the isolation is a
 * domain rule, tested independently.</p>
 *
 * <p>Read-only; no transaction is opened here — each provider manages its own read transaction over its
 * own tables. Counts only ever cross the boundary, never PII (PRD §18).</p>
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final List<ModuleStatsProvider> providers;
    private final ClockPort clock;

    /**
     * @param providers every {@link ModuleStatsProvider} bean Spring found (may be empty before any module
     *                  publishes one — the dashboard then renders with no sections rather than failing).
     * @param clock     time source for the {@code generatedAt} stamp (testable — never {@code Instant.now()}
     *                  inline).
     */
    public DashboardService(List<ModuleStatsProvider> providers, ClockPort clock) {
        this.providers = providers;
        this.clock = clock;
    }

    /**
     * Computes the current platform dashboard by invoking each provider with fault isolation.
     *
     * @return the aggregated stats — one section per provider, ordered by section name for a stable UI,
     *         plus the generation instant.
     */
    public DashboardStatsDto compute() {
        List<DashboardSectionDto> sections = new ArrayList<>();
        for (ModuleStatsProvider provider : providers) {
            sections.add(invoke(provider));
        }
        sections.sort(Comparator.comparing(DashboardSectionDto::section));
        return new DashboardStatsDto(List.copyOf(sections), clock.now());
    }

    /**
     * Invokes one provider, converting any failure into a {@code degraded} section rather than propagating
     * it (so one module never breaks the whole dashboard).
     */
    private DashboardSectionDto invoke(ModuleStatsProvider provider) {
        String section = safeSection(provider);
        try {
            List<ModuleStat> stats = provider.stats();
            if (stats == null) {
                // A provider that returns null violates the contract; treat as degraded, never NPE the UI.
                log.warn("Dashboard provider '{}' returned null stats; reporting section as degraded", section);
                return new DashboardSectionDto(section, List.of(), true);
            }
            return new DashboardSectionDto(section, List.copyOf(stats), false);
        } catch (RuntimeException ex) {
            // Isolate the failure: log it (no PII — provider sections carry counts only) and degrade the tile.
            log.warn("Dashboard provider '{}' failed to compute stats; reporting section as degraded", section, ex);
            return new DashboardSectionDto(section, List.of(), true);
        }
    }

    /** Reads a provider's section name defensively (a buggy provider must not crash aggregation). */
    private String safeSection(ModuleStatsProvider provider) {
        try {
            String section = provider.section();
            return (section == null || section.isBlank())
                    ? provider.getClass().getSimpleName()
                    : section;
        } catch (RuntimeException ex) {
            return provider.getClass().getSimpleName();
        }
    }
}
