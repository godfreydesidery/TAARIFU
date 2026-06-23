package com.taarifu.reporting.api.dto;

import java.util.UUID;

/**
 * The PII-free administrative scope of a report — its ward (Kata) and issue category — published for
 * cross-module horizontal-authorization checks (ADR-0013 §1; R-1).
 *
 * <p>Responsibility: carry exactly the two ids a sibling needs to decide "may this agent act on this report
 * within their area/category scope?" — the report's {@code reporterWardId} and its category public id — and
 * nothing else. WHY only these two (data minimisation, PRD §18): the responders module gates its case
 * lifecycle on the live {@code RoleAssignment} area/category scope (R-1); it must not receive the reporter,
 * description, geo-point, or any other report content to do so. No PII crosses this boundary.</p>
 *
 * @param wardPublicId     the report's resolved ward {@code publicId} (minimum pin granularity, PRD §9.0);
 *                         the area the action targets for {@code canActOnArea}.
 * @param categoryPublicId the report's issue category {@code publicId}; the category the action targets for
 *                         {@code canActOnCategory}.
 */
public record ReportScopeDto(UUID wardPublicId, UUID categoryPublicId) {
}
