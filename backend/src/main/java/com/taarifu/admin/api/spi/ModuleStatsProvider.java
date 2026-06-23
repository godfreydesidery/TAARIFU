package com.taarifu.admin.api.spi;

import java.util.List;

/**
 * The <b>provider SPI</b> a module implements so the admin console can aggregate its headline counts onto
 * the platform dashboard <b>without the admin module reaching into that module's internals</b> (M14,
 * US-14.1, UC-H06; ADR-0013 §1, ARCHITECTURE §3.2).
 *
 * <p>Responsibility: invert the dashboard dependency. The admin module needs cross-module aggregates
 * (reports by status, open cases, reps onboarded, verification-queue depth, users by tier, flags pending)
 * but must not import {@code reporting}/{@code identity}/{@code moderation}/… {@code domain}/{@code
 * repository} (forbidden by ARCHITECTURE §3.2 and the {@code ModuleBoundaryTest}). Instead each owning
 * module publishes a {@code @Component} implementing this interface — a sanctioned feature→foundation
 * {@code api} dependency — that returns its own {@link ModuleStat}s computed over its own tables. Admin
 * injects {@code List<ModuleStatsProvider>} (Spring collects every implementation) and merges them. New
 * modules add a tile by adding a provider; the admin module never changes (open/closed, CLAUDE.md §3).</p>
 *
 * <p>WHY a pull-SPI here rather than each module exposing a bespoke {@code *StatsApi} the admin calls
 * one-by-one (the {@code *QueryApi} shape of ADR-0013 §1): the dashboard needs <b>every</b> module's
 * counts at once and must stay open to new modules. A one-by-one model would hard-code the admin service
 * to the current module set and force an admin edit per new module; the collected-{@code List} SPI keeps
 * admin closed to modification and the boundary mechanically enforced. The contract still obeys ADR-0013
 * (a published {@code api} interface, only DTOs cross). Each provider is a synchronous, read-only,
 * in-process call; a slow/failing provider must be isolated by the aggregator so one module cannot break
 * the whole dashboard (the admin service degrades that tile, ARCHITECTURE §1 "degrade gracefully").</p>
 *
 * <p><b>PII/privacy:</b> a provider returns <b>counts only</b> — never a citizen's identity, phone, or
 * {@code idNo}. The dashboard is an operational overview, not a data export (PRD §18, PDPA).</p>
 */
public interface ModuleStatsProvider {

    /**
     * @return a stable machine name for the contributing module/section (e.g. {@code reporting},
     *         {@code identity}, {@code moderation}); used to namespace and group the tiles and to attribute
     *         a failing provider in logs. Never null/blank.
     */
    String section();

    /**
     * Computes this module's headline counts for the platform dashboard.
     *
     * <p>Read-only and side-effect-free. Implementations should be cheap (indexed COUNTs); the aggregator
     * may cache. An implementation that cannot compute a metric returns the metrics it can and omits the
     * rest rather than throwing — but if it does throw, the admin aggregator isolates it (that section is
     * reported as degraded, the rest of the dashboard still renders).</p>
     *
     * @return this module's stats; an empty list if the module currently has nothing to report (never
     *         {@code null}).
     */
    List<ModuleStat> stats();
}
