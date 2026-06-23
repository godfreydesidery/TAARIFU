package com.taarifu.admin.api.dto;

import com.taarifu.admin.api.spi.ModuleStat;

import java.util.List;

/**
 * One module's slice of the platform dashboard — its section name, its counts, and whether the section
 * could be computed (M14, UC-H06).
 *
 * <p>Responsibility: the per-section response shape the admin console renders. {@link #degraded} is
 * {@code true} when the contributing {@link com.taarifu.admin.api.spi.ModuleStatsProvider provider} threw
 * or was unavailable, so the UI can show "stats unavailable" for that tile while the rest of the dashboard
 * still renders — a failing module never blanks the whole dashboard (ARCHITECTURE §1 degrade gracefully).</p>
 *
 * @param section  the contributing module's machine name (e.g. {@code reporting}, {@code identity}).
 * @param stats    the section's counts (empty when {@link #degraded}).
 * @param degraded {@code true} if the section's stats could not be computed.
 */
public record DashboardSectionDto(String section, List<ModuleStat> stats, boolean degraded) {
}
