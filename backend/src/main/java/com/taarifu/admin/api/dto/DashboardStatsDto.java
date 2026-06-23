package com.taarifu.admin.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The aggregated platform dashboard payload (M14, US-14.1, UC-H06).
 *
 * <p>Responsibility: the single response the admin console fetches for its overview — every contributing
 * module's {@link DashboardSectionDto section} plus the {@code generatedAt} instant the aggregate was
 * computed (so the UI can show freshness / drive a cache). Counts only; no PII (PRD §18).</p>
 *
 * @param sections    the per-module sections, in a stable order.
 * @param generatedAt when the aggregate was computed (UTC).
 */
public record DashboardStatsDto(List<DashboardSectionDto> sections, Instant generatedAt) {
}
