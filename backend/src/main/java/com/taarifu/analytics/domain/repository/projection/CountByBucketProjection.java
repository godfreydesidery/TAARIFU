package com.taarifu.analytics.domain.repository.projection;

import java.time.Instant;

/**
 * A "(bucketStart, count)" row returned by a time-bucketed (trend) analytics aggregation
 * (PRD §3.3 trends; Appendix C; ADR-0020 §1; M15).
 *
 * <p>Responsibility: the lightweight Spring Data projection for any "count per time bucket" series —
 * reports volume over time and the SLA-breach trend. {@code bucketStart} is the truncated
 * {@code date_trunc(...)} instant for the bucket (its inclusive start); {@code count} is the number of
 * events that fell in it. Keeping one shared shape (mirroring {@link CountByKeyProjection}) avoids a
 * bespoke projection per trend query (DRY) while never exposing the entity (ADR-0013).</p>
 */
public interface CountByBucketProjection {

    /** @return the inclusive start of the time bucket (the truncated {@code occurred_at}, UTC). */
    Instant getBucketStart();

    /** @return the number of events in this bucket. */
    long getCount();
}
