package com.taarifu.admin.api.dto;

import com.taarifu.reporting.api.dto.ReportStatusCount;

import java.time.Instant;
import java.util.List;

/**
 * The flattened aggregate-counts payload the admin console's overview header needs (M14, US-14.1, UC-H06).
 *
 * <p>Responsibility: a focused, named projection of the handful of headline numbers the back-office
 * dashboard renders — reports broken down by lifecycle status, the open-case total, the verification-queue
 * depth, and the count of moderation flags pending. WHY this is distinct from the existing
 * {@link DashboardStatsDto} (at {@code /admin/dashboard/stats}): that endpoint is the open/closed,
 * per-module {@code ModuleStatsProvider} aggregation (every module's tiles, degrade-gracefully); this one
 * is the named, typed shape the overview header binds to directly so the UI need not key-match strings.
 * Both are counts only — no PII (PRD §18).</p>
 *
 * <p><b>Sourcing &amp; isolation:</b> the report numbers come from the reporting module's published
 * {@link com.taarifu.reporting.api.ReportQueryApi} (sanctioned {@code admin → reporting} api call,
 * ADR-0013 §1). The verification-queue depth (identity) and flags-pending (moderation) are pulled from
 * those modules' published {@link com.taarifu.admin.api.spi.ModuleStatsProvider} stats by stable key — so
 * the admin module never imports identity/moderation internals; until those modules publish a provider the
 * counts default to {@code 0} (the overview degrades gracefully, never errors).</p>
 *
 * @param reportsByStatus       per-status report counts (a status with no reports is omitted).
 * @param openCases             total reports in a non-terminal status.
 * @param slaBreachedCases      total still-active reports whose SLA {@code dueAt} has passed.
 * @param verificationQueueDepth number of identity verifications awaiting review (0 if unavailable).
 * @param flagsPending          number of moderation flags awaiting action (0 if unavailable).
 * @param generatedAt           when the aggregate was computed (UTC), for UI freshness.
 */
public record AdminStatsDto(
        List<ReportStatusCount> reportsByStatus,
        long openCases,
        long slaBreachedCases,
        long verificationQueueDepth,
        long flagsPending,
        Instant generatedAt
) {
}
