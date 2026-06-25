package com.taarifu.responders.api.dto;

import com.taarifu.responders.domain.model.enums.ProviderSelectionMode;
import com.taarifu.responders.domain.model.enums.ResponderType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request DTO to create a routing rule (admin CRUD, PRD §24.2).
 *
 * <p>Responsibility: validated input for {@code POST /routing-rules}. Category/sub-category/preferred-
 * responder are referenced by public id. The category and sub-category ids are existence-validated against
 * reporting's published {@code IssueCategoryQueryApi} in {@code ResponderAdminService.createRoutingRule}
 * (sync {@code responders → reporting}, ADR-0013 §4a) before the rule is persisted; the responders
 * {@code RoutingHandler} consumes the resulting rules to resolve a routed report's OWNER responder
 * (async, D21).</p>
 *
 * @param categoryPublicId      the routed reporting-category id (required).
 * @param subCategoryPublicId   optional sub-category id.
 * @param responderType         the responder kind/sector to route to (required).
 * @param selectionMode         AUTO_BY_AREA or CITIZEN_SELECTED (required).
 * @param preferredResponderId  optional pinned responder id.
 * @param priority              evaluation priority (lower wins); defaults applied if {@code null}.
 */
public record CreateRoutingRuleRequest(
        @NotNull UUID categoryPublicId,
        UUID subCategoryPublicId,
        @NotNull ResponderType responderType,
        @NotNull ProviderSelectionMode selectionMode,
        UUID preferredResponderId,
        Integer priority
) {
}
