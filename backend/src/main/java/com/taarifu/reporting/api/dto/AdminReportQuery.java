package com.taarifu.reporting.api.dto;

import java.util.UUID;

/**
 * The PII-free filter for the admin/owner-grade report queue (M14 admin console; PRD §10 US-3.4,
 * §24.3 owner view) published on {@link com.taarifu.reporting.api.ReportQueryApi}.
 *
 * <p>Responsibility: carry exactly the filter dimensions the back-office reports queue needs — lifecycle
 * {@code status}, issue {@code categoryId}, administrative {@code wardId} (Kata; minimum pin granularity,
 * PRD §9.0), and an {@code slaBreached} flag — and nothing else. Every field is optional ({@code null} =
 * no constraint on that dimension); the admin controller builds this from request params and the reporting
 * side resolves it over its own tables. WHY a typed query record (not a bag of nullable service args):
 * keeps the cross-module contract explicit and append-only, and lets the admin module name its filters
 * without importing reporting's internals (ADR-0013 §1).</p>
 *
 * <p>WHY {@code status} is the enum <i>name</i> as a {@code String}, not the enum itself: the enum
 * {@link com.taarifu.reporting.domain.model.enums.ReportStatus} is a reporting-internal domain type and
 * must not leak across the module boundary as a type (CLAUDE.md §8). The reporting impl validates/parses
 * the name and treats an unknown value as "no match" rather than throwing — a stale admin filter never
 * 500s the queue.</p>
 *
 * @param status      lifecycle status name to filter to (e.g. {@code NEW}, {@code IN_PROGRESS},
 *                    {@code RESOLVED}); {@code null}/blank means any status.
 * @param categoryId  issue category {@code publicId} to filter to; {@code null} means any category.
 * @param wardId      ward (Kata) {@code publicId} to filter to; {@code null} means any ward.
 * @param slaBreached when {@code true}, only cases whose SLA {@code dueAt} has passed and that are still
 *                    active (open) are returned; when {@code false} only non-breached are returned;
 *                    {@code null} means do not filter on breach.
 */
public record AdminReportQuery(
        String status,
        UUID categoryId,
        UUID wardId,
        Boolean slaBreached
) {
}
