package com.taarifu.responders.application.mapper;

import com.taarifu.responders.api.dto.OrganisationDto;
import com.taarifu.responders.api.dto.ResponderAssignmentDto;
import com.taarifu.responders.api.dto.ResponderDto;
import com.taarifu.responders.api.dto.RoutingRuleDto;
import com.taarifu.responders.domain.model.Organisation;
import com.taarifu.responders.domain.model.Responder;
import com.taarifu.responders.domain.model.ResponderAssignment;
import com.taarifu.responders.domain.model.RoutingRule;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps responder-module entities to their boundary DTOs (ARCHITECTURE.md §3.3, CLAUDE.md §8).
 *
 * <p>Responsibility: the single translation layer ensuring <b>entities never leave the module</b> and
 * only {@code publicId}s are exposed, never internal numeric ids (ADR-0006). Hand-written for explicit,
 * documented null-handling, matching the geography slice's mapper style (no annotation-processor
 * dependency for these trivial mappings).</p>
 */
@Component
public class ResponderMapper {

    /**
     * @param org an organisation.
     * @return its boundary DTO.
     */
    public OrganisationDto toOrganisationDto(Organisation org) {
        return new OrganisationDto(
                org.getPublicId(),
                org.getName(),
                org.getType().name(),
                org.getStatus().name(),
                org.isVerified(),
                org.getContactPhone(),
                org.getContactEmail(),
                org.getWebsiteUrl());
    }

    /**
     * @param responder a responder capability.
     * @return its boundary DTO, including handled categories and coverage areas as id lists.
     */
    public ResponderDto toResponderDto(Responder responder) {
        Organisation org = responder.getOrganisation();
        return new ResponderDto(
                responder.getPublicId(),
                org != null ? org.getPublicId() : null,
                org != null ? org.getName() : null,
                responder.getName(),
                responder.getResponderType().name(),
                responder.getStatus().name(),
                responder.getCoverageType().name(),
                List.copyOf(responder.getHandledCategoryIds()),
                List.copyOf(responder.getCoverageAreaIds()),
                responder.getSlaPolicy());
    }

    /**
     * @param rule a routing rule.
     * @return its boundary DTO.
     */
    public RoutingRuleDto toRoutingRuleDto(RoutingRule rule) {
        return new RoutingRuleDto(
                rule.getPublicId(),
                rule.getCategoryPublicId(),
                rule.getSubCategoryPublicId(),
                rule.getResponderType().name(),
                rule.getSelectionMode().name(),
                rule.getPreferredResponder() != null ? rule.getPreferredResponder().getPublicId() : null,
                rule.getPriority(),
                rule.isActive());
    }

    /**
     * @param assignment a responder assignment.
     * @return its boundary DTO.
     */
    public ResponderAssignmentDto toAssignmentDto(ResponderAssignment assignment) {
        Responder responder = assignment.getResponder();
        return new ResponderAssignmentDto(
                assignment.getPublicId(),
                assignment.getReportId(),
                responder != null ? responder.getPublicId() : null,
                responder != null ? responder.getName() : null,
                assignment.getRole().name(),
                assignment.getStatus().name(),
                assignment.getAssignedAt(),
                assignment.getSlaPolicy());
    }
}
