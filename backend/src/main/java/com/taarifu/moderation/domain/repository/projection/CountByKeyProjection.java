package com.taarifu.moderation.domain.repository.projection;

/**
 * A generic "(key, count)" row returned by the moderation transparency-report aggregations
 * (PRD §25 transparency reporting, M-Phase 3; ADR-0018).
 *
 * <p>Responsibility: the lightweight Spring Data projection for any single-dimension count over the
 * moderation tables — actions by type, appeals by outcome, flags by reason, items by auto-vs-manual. One
 * shared shape avoids a bespoke projection per query (DRY) while staying a read model that never exposes the
 * entity past the module boundary.</p>
 *
 * <p>WHY a moderation-owned copy (not the analytics module's identical projection): the transparency report
 * aggregates moderation's own append-only tables and must not depend on a sibling module's internal
 * {@code domain.repository} package (ARCHITECTURE §3.2; ADR-0013). The {@code key} is always a code/enum name
 * — never a subject id, author, or any PII.</p>
 */
public interface CountByKeyProjection {

    /** @return the group key (an enum name / code / boolean-as-string); never a person, location, or content. */
    String getKey();

    /** @return the number of rows in this group. */
    long getCount();
}
