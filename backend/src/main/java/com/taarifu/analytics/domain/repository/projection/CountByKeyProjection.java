package com.taarifu.analytics.domain.repository.projection;

/**
 * A generic "(key, count)" row returned by grouped analytics aggregations
 * (PRD Appendix C dashboards; M15).
 *
 * <p>Responsibility: the lightweight Spring Data projection for any single-dimension count —
 * reports by category, escalations by breach type, moderation actions by outcome, channel mix,
 * verification-funnel steps, engagement counts. Keeping one shared shape avoids a bespoke projection
 * per query (DRY) while staying a DTO-like read model that never exposes the entity (ADR-0013).</p>
 *
 * <p>The {@code key} is the {@code String} form of whatever was grouped (an enum name, an outcome code,
 * or a public-id string); the service maps it into the typed API DTO.</p>
 */
public interface CountByKeyProjection {

    /** @return the group key (enum name / outcome code / id string); may be {@code null} for an unattributed bucket. */
    String getKey();

    /** @return the number of events in this group. */
    long getCount();
}
