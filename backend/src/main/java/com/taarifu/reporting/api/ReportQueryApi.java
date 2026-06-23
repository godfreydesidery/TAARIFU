package com.taarifu.reporting.api;

import com.taarifu.reporting.api.dto.ReportScopeDto;

import java.util.UUID;

/**
 * The reporting module's <b>public, in-process query port</b> for validating a report's existence
 * (ADR-0013 §1, §4a; D21). The responders module calls this (synchronous {@code responders → reporting})
 * before binding an assignment to a report, without importing reporting's internals (ARCHITECTURE §3.2).
 *
 * <p>Responsibility: answer "does this report exist?" so a sibling never creates an assignment/relationship
 * against a non-existent report, and "what is this report's administrative scope (ward + category)?" so a
 * sibling can run a horizontal-authorization check (R-1) without importing reporting's internals. The caller
 * treats the result as opaque truth.</p>
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

    /**
     * Resolves the report's administrative scope — its ward (Kata) and issue category public ids — for a
     * sibling's horizontal-authorization check (R-1, ADR-0013 §1).
     *
     * <p>WHY this exists (R-1): the responders module's case-lifecycle endpoints (assign/start/resolve/
     * escalate) were role-gated only; an agent could drive <b>any</b> report's lifecycle regardless of their
     * {@code RoleAssignment} area/category scope. The responders side now resolves the report's ward +
     * category through this port and gates on {@code @taarifuAuthz.canActOnArea/canActOnCategory} (live
     * scope, never token). The port returns only the two PII-free ids ({@link ReportScopeDto}) — data
     * minimisation, no report content crosses the boundary.</p>
     *
     * @param reportPublicId the report's public id.
     * @return the report's ward + category public ids.
     * @throws com.taarifu.common.error.ApiException with {@code NOT_FOUND} if no such report exists.
     */
    ReportScopeDto scopeOf(UUID reportPublicId);
}
