package com.taarifu.reporting.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public, PII-free report response DTO for the near-me list/map (PRD §10 US-3.7, §25.3).
 *
 * <p>Responsibility: the boundary shape for the public reports endpoint. It is deliberately a <b>reduced
 * projection</b> that <b>omits the reporter entirely</b> — no reporter id, no reporter context — and
 * exposes only what a public viewer may see: category, title, ward, status, counters, and the filed time.
 * Only PUBLIC reports are ever mapped to this shape (the service + repository both filter visibility).</p>
 *
 * <p>WHY no precise geo-point here: for sensitive/PRIVATE reports the point is redacted (Appendix D.4),
 * and even for public reports the near-me view works at ward granularity; exposing exact coordinates of
 * every report invites harm (e.g. pinpointing a complainant's home). The ward is the public locator.</p>
 *
 * @param id           the report's public id.
 * @param code         the human ticket code (safe to show; not linkable to identity for anonymous filings).
 * @param categoryId   the issue category public id.
 * @param categoryName the issue category display name.
 * @param title        the title.
 * @param wardId       the ward public id (the public locator; no precise point).
 * @param status       lifecycle status name.
 * @param priority     priority name.
 * @param upvotes      discovery-reach upvote count.
 * @param followers    discovery-reach follower count.
 * @param createdAt    filed instant (UTC).
 */
public record PublicReportDto(
        UUID id,
        String code,
        UUID categoryId,
        String categoryName,
        String title,
        UUID wardId,
        String status,
        String priority,
        long upvotes,
        long followers,
        Instant createdAt
) {
}
