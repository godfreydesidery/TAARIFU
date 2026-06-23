package com.taarifu.responders.api.dto;

import java.util.UUID;

/**
 * Response DTO for a {@link com.taarifu.responders.domain.model.RoutingRule} (PRD §24.2).
 *
 * <p>Responsibility: the boundary shape for admin management of the routing table and for the reporting
 * module (via this module's API) to learn how a category routes. Category/sub-category/preferred-
 * responder are exposed as public ids (references to the reporting/this module's entities).</p>
 *
 * @param id                    the rule's public id (UUID).
 * @param categoryPublicId      the routed reporting-category id.
 * @param subCategoryPublicId   optional sub-category id, or {@code null}.
 * @param responderType         the responder kind/sector routed to.
 * @param selectionMode         {@code AUTO_BY_AREA} or {@code CITIZEN_SELECTED}.
 * @param preferredResponderId  optional pinned responder id, or {@code null}.
 * @param priority              evaluation priority (lower wins).
 * @param active                whether the rule participates in routing.
 */
public record RoutingRuleDto(
        UUID id,
        UUID categoryPublicId,
        UUID subCategoryPublicId,
        String responderType,
        String selectionMode,
        UUID preferredResponderId,
        int priority,
        boolean active
) {
}
