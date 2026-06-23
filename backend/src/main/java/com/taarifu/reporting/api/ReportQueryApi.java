package com.taarifu.reporting.api;

import java.util.UUID;

/**
 * The reporting module's <b>public, in-process query port</b> for validating a report's existence
 * (ADR-0013 §1, §4a; D21). The responders module calls this (synchronous {@code responders → reporting})
 * before binding an assignment to a report, without importing reporting's internals (ARCHITECTURE §3.2).
 *
 * <p>Responsibility: answer "does this report exist?" so a sibling never creates an assignment/relationship
 * against a non-existent report. The caller treats the result as opaque truth.</p>
 */
public interface ReportQueryApi {

    /**
     * Asserts the report exists (and is not soft-deleted), throwing if not.
     *
     * @param reportPublicId the report's public id.
     * @throws com.taarifu.common.error.ApiException with {@code NOT_FOUND} if no such report exists.
     */
    void requireExists(UUID reportPublicId);

    /**
     * @param reportPublicId the report's public id.
     * @return {@code true} if a non-deleted report with that id exists.
     */
    boolean exists(UUID reportPublicId);
}
