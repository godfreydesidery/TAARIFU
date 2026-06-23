package com.taarifu.responders.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.reporting.api.IssueCategoryQueryApi;
import com.taarifu.reporting.api.ReportLifecycleApi;
import com.taarifu.reporting.api.ReportQueryApi;
import com.taarifu.reporting.api.dto.ReportDto;
import com.taarifu.responders.api.dto.CreateAssignmentRequest;
import com.taarifu.responders.api.dto.CreateOrganisationRequest;
import com.taarifu.responders.api.dto.CreateResponderRequest;
import com.taarifu.responders.api.dto.CreateRoutingRuleRequest;
import com.taarifu.responders.api.dto.OrganisationDto;
import com.taarifu.responders.api.dto.ResponderAssignmentDto;
import com.taarifu.responders.api.dto.ResponderDto;
import com.taarifu.responders.api.dto.RoutingRuleDto;
import com.taarifu.responders.api.dto.UpdateOrganisationRequest;
import com.taarifu.responders.api.dto.UpdateResponderRequest;
import com.taarifu.responders.application.mapper.ResponderMapper;
import com.taarifu.responders.domain.model.Organisation;
import com.taarifu.responders.domain.model.Responder;
import com.taarifu.responders.domain.model.ResponderAssignment;
import com.taarifu.responders.domain.model.RoutingRule;
import com.taarifu.responders.domain.model.enums.AssignmentRole;
import com.taarifu.responders.domain.repository.OrganisationRepository;
import com.taarifu.responders.domain.repository.ResponderAssignmentRepository;
import com.taarifu.responders.domain.repository.ResponderRepository;
import com.taarifu.responders.domain.repository.RoutingRuleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.UUID;

/**
 * Application service for <b>administering</b> the responder directory — organisation/responder CRUD,
 * verification, routing rules, and report assignments (PRD §24, D20/D21).
 *
 * <p>Responsibility: the transactional orchestration behind the Moderator/Admin endpoints. It owns the
 * write transaction boundary, returns DTOs (never entities), and enforces the module's integrity
 * invariants in Java as a typed-error layer in front of the DB constraints (defense in depth):</p>
 * <ul>
 *   <li><b>Single OWNER per report</b> (§24.3): adding a second OWNER, or a duplicate assignment of the
 *       same responder to the same report, throws {@link ErrorCode#CONFLICT} before the DB partial-unique
 *       index fires — giving the caller a clean, localised error.</li>
 *   <li><b>Verification is separate from edit</b> (§24.4): verification has its own method/endpoint so it
 *       is independently authorised and audited; an org always starts PENDING + unverified.</li>
 * </ul>
 *
 * <p>WHY method-security (role checks) lives on the controllers, not here: authorization is declared at
 * the API edge with {@code @PreAuthorize} (ARCHITECTURE.md §6.2) so the gate is greppable per endpoint;
 * this service assumes it is only reached by an authorised caller and focuses on integrity + persistence.</p>
 */
@Service
@Transactional
public class ResponderAdminService {

    private final OrganisationRepository organisationRepository;
    private final ResponderRepository responderRepository;
    private final RoutingRuleRepository routingRuleRepository;
    private final ResponderAssignmentRepository assignmentRepository;
    private final ReportQueryApi reportQueryApi;
    private final IssueCategoryQueryApi issueCategoryQueryApi;
    private final ReportLifecycleApi reportLifecycleApi;
    private final ResponderMapper mapper;
    private final ClockPort clock;

    /**
     * @param organisationRepository organisation persistence.
     * @param responderRepository    responder persistence.
     * @param routingRuleRepository  routing-rule persistence.
     * @param assignmentRepository   assignment persistence.
     * @param reportQueryApi         reporting's published port validating a {@code reportId} exists (D21).
     * @param issueCategoryQueryApi  reporting's published port validating a {@code categoryId} (ADR-0013).
     * @param reportLifecycleApi     reporting's published command port driving the case state machine (D21).
     * @param mapper                 entity→DTO mapper.
     * @param clock                  injectable time (assignment timestamps; testability).
     */
    public ResponderAdminService(OrganisationRepository organisationRepository,
                                 ResponderRepository responderRepository,
                                 RoutingRuleRepository routingRuleRepository,
                                 ResponderAssignmentRepository assignmentRepository,
                                 ReportQueryApi reportQueryApi,
                                 IssueCategoryQueryApi issueCategoryQueryApi,
                                 ReportLifecycleApi reportLifecycleApi,
                                 ResponderMapper mapper,
                                 ClockPort clock) {
        this.organisationRepository = organisationRepository;
        this.responderRepository = responderRepository;
        this.routingRuleRepository = routingRuleRepository;
        this.assignmentRepository = assignmentRepository;
        this.reportQueryApi = reportQueryApi;
        this.issueCategoryQueryApi = issueCategoryQueryApi;
        this.reportLifecycleApi = reportLifecycleApi;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------------------------
    // Organisations
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers a new organisation (PENDING + unverified, §24.4).
     *
     * @param request validated creation input.
     * @return the created {@link OrganisationDto}.
     */
    public OrganisationDto createOrganisation(CreateOrganisationRequest request) {
        Organisation org = Organisation.create(request.name(), request.type());
        org.setContacts(request.contactPhone(), request.contactEmail(), request.websiteUrl());
        return mapper.toOrganisationDto(organisationRepository.save(org));
    }

    /**
     * Updates an organisation's mutable fields (not verification, §24.4).
     *
     * @param publicId organisation public id.
     * @param request  validated update input.
     * @return the updated {@link OrganisationDto}.
     * @throws ResourceNotFoundException if no such organisation.
     */
    public OrganisationDto updateOrganisation(UUID publicId, UpdateOrganisationRequest request) {
        Organisation org = requireOrganisation(publicId);
        org.rename(request.name());
        org.changeType(request.type());
        org.changeStatus(request.status());
        org.setContacts(request.contactPhone(), request.contactEmail(), request.websiteUrl());
        return mapper.toOrganisationDto(org);
    }

    /**
     * Verifies or un-verifies an organisation (Moderator/Admin, §24.4). Un-verifying immediately
     * removes it from the public directory.
     *
     * @param publicId organisation public id.
     * @param verified the new verification state.
     * @return the updated {@link OrganisationDto}.
     * @throws ResourceNotFoundException if no such organisation.
     */
    public OrganisationDto setOrganisationVerified(UUID publicId, boolean verified) {
        Organisation org = requireOrganisation(publicId);
        org.setVerified(verified);
        return mapper.toOrganisationDto(org);
    }

    /**
     * Lists all organisations (admin view, any status), paged.
     *
     * @param pageable paging/sorting.
     * @return a page of {@link OrganisationDto}.
     */
    @Transactional(readOnly = true)
    public Page<OrganisationDto> listAllOrganisations(Pageable pageable) {
        return organisationRepository.findAllBy(pageable).map(mapper::toOrganisationDto);
    }

    // ---------------------------------------------------------------------------------------------
    // Responders
    // ---------------------------------------------------------------------------------------------

    /**
     * Creates a responder capability under an organisation (PENDING, §24.1).
     *
     * @param organisationPublicId the owning organisation's public id.
     * @param request              validated creation input.
     * @return the created {@link ResponderDto}.
     * @throws ResourceNotFoundException if the organisation does not exist.
     */
    public ResponderDto createResponder(UUID organisationPublicId, CreateResponderRequest request) {
        Organisation org = requireOrganisation(organisationPublicId);
        // Validate each handled category exists via reporting's published port (sync responders → reporting,
        // ADR-0013 §4a) so a responder can never be configured to handle a non-existent category.
        validateCategories(request.handledCategoryIds());
        Responder responder = Responder.create(org, request.name(), request.responderType(),
                request.coverageType());
        responder.setHandledCategoryIds(request.handledCategoryIds() == null
                ? null : new HashSet<>(request.handledCategoryIds()));
        responder.setCoverageAreaIds(request.coverageAreaIds() == null
                ? null : new HashSet<>(request.coverageAreaIds()));
        responder.setSlaPolicy(request.slaPolicy());
        return mapper.toResponderDto(responderRepository.save(responder));
    }

    /**
     * Updates a responder capability's configuration (§24.1).
     *
     * @param publicId responder public id.
     * @param request  validated update input.
     * @return the updated {@link ResponderDto}.
     * @throws ResourceNotFoundException if no such responder.
     */
    public ResponderDto updateResponder(UUID publicId, UpdateResponderRequest request) {
        Responder responder = requireResponder(publicId);
        // Validate the (replacement) handled categories exist via reporting's published port (ADR-0013 §4a).
        validateCategories(request.handledCategoryIds());
        responder.rename(request.name());
        responder.changeResponderType(request.responderType());
        responder.changeStatus(request.status());
        responder.changeCoverageType(request.coverageType());
        responder.setHandledCategoryIds(request.handledCategoryIds() == null
                ? null : new HashSet<>(request.handledCategoryIds()));
        responder.setCoverageAreaIds(request.coverageAreaIds() == null
                ? null : new HashSet<>(request.coverageAreaIds()));
        responder.setSlaPolicy(request.slaPolicy());
        return mapper.toResponderDto(responder);
    }

    /**
     * Lists the responders of an organisation (admin view, any status), paged.
     *
     * @param organisationPublicId owning organisation public id.
     * @param pageable             paging/sorting.
     * @return a page of {@link ResponderDto}.
     */
    @Transactional(readOnly = true)
    public Page<ResponderDto> listRespondersOfOrganisation(UUID organisationPublicId, Pageable pageable) {
        // Validate the organisation exists so an unknown id is a clean 404, not an empty page.
        requireOrganisation(organisationPublicId);
        return responderRepository.findByOrganisationPublicId(organisationPublicId, pageable)
                .map(mapper::toResponderDto);
    }

    // ---------------------------------------------------------------------------------------------
    // Routing rules
    // ---------------------------------------------------------------------------------------------

    /**
     * Creates a routing rule (§24.2). The {@code categoryPublicId} (and {@code subCategoryPublicId} if
     * given) are validated against reporting's published {@link IssueCategoryQueryApi} (sync
     * {@code responders → reporting}, ADR-0013 §4a) so a rule can never route a non-existent category.
     *
     * @param request validated creation input.
     * @return the created {@link RoutingRuleDto}.
     * @throws ResourceNotFoundException if {@code preferredResponderId} is given but unknown, or the
     *                                   category/sub-category does not exist.
     */
    public RoutingRuleDto createRoutingRule(CreateRoutingRuleRequest request) {
        // Validate the routed category exists via reporting's published port (sync responders → reporting).
        issueCategoryQueryApi.requireCategory(request.categoryPublicId());
        if (request.subCategoryPublicId() != null) {
            issueCategoryQueryApi.requireCategory(request.subCategoryPublicId());
        }
        RoutingRule rule = RoutingRule.create(request.categoryPublicId(), request.responderType(),
                request.selectionMode());
        rule.setSubCategoryPublicId(request.subCategoryPublicId());
        if (request.priority() != null) {
            rule.setPriority(request.priority());
        }
        if (request.preferredResponderId() != null) {
            rule.setPreferredResponder(requireResponder(request.preferredResponderId()));
        }
        return mapper.toRoutingRuleDto(routingRuleRepository.save(rule));
    }

    /**
     * Lists all routing rules (admin view), paged.
     *
     * @param pageable paging/sorting.
     * @return a page of {@link RoutingRuleDto}.
     */
    @Transactional(readOnly = true)
    public Page<RoutingRuleDto> listRoutingRules(Pageable pageable) {
        return routingRuleRepository.findAllBy(pageable).map(mapper::toRoutingRuleDto);
    }

    // ---------------------------------------------------------------------------------------------
    // Assignments (multisectoral: one OWNER + collaborators) — PRD §24.3
    // ---------------------------------------------------------------------------------------------

    /**
     * Assigns a responder to a report as OWNER or COLLABORATOR, enforcing the single-OWNER and
     * no-duplicate invariants (PRD §24.3).
     *
     * <p>WHY the guards here (in addition to the DB partial-unique indexes): the DB is the ultimate
     * authority, but checking first lets the caller receive a typed, localised {@link ErrorCode#CONFLICT}
     * instead of a raw constraint-violation, and documents the invariant at the application layer
     * (defense in depth). The report is referenced by id only — its existence is validated synchronously
     * via reporting's published {@link ReportQueryApi} (the {@code responders → reporting} read direction,
     * ADR-0013 §4a), so an assignment can never be bound to a non-existent report (D21).</p>
     *
     * @param reportId               the report id (from the path).
     * @param request                validated assignment input (responder + role).
     * @param assignedByUserPublicId the authenticated assigning user's id (for audit/attribution).
     * @return the created {@link ResponderAssignmentDto}.
     * @throws ResourceNotFoundException if the responder or the report does not exist.
     * @throws ApiException {@link ErrorCode#CONFLICT} if a second OWNER is attempted, or the responder is
     *                      already assigned to this report.
     */
    public ResponderAssignmentDto assignResponder(UUID reportId, CreateAssignmentRequest request,
                                                  UUID assignedByUserPublicId) {
        // Validate the report exists via reporting's published port (sync responders → reporting, D21).
        reportQueryApi.requireExists(reportId);

        Responder responder = requireResponder(request.responderId());

        // Duplicate guard: the same responder may hold at most one live assignment per report.
        assignmentRepository.findByReportAndResponder(reportId, request.responderId())
                .ifPresent(a -> {
                    throw new ApiException(ErrorCode.CONFLICT, "responders.assignment.duplicate", reportId);
                });

        // Single-OWNER guard (§24.3): a report has exactly one accountable owner.
        if (request.role() == AssignmentRole.OWNER) {
            assignmentRepository.findOwner(reportId, AssignmentRole.OWNER)
                    .ifPresent(a -> {
                        throw new ApiException(ErrorCode.CONFLICT, "responders.assignment.ownerExists", reportId);
                    });
        }

        ResponderAssignment assignment = ResponderAssignment.create(
                reportId, responder, request.role(), assignedByUserPublicId, clock.now());
        assignment.setSlaPolicy(request.slaPolicy());
        // TODO(wiring): publish ResponderAssignedEvent via the transactional outbox once the bus lands.
        return mapper.toAssignmentDto(assignmentRepository.save(assignment));
    }

    /**
     * Lists all live assignments of a report (owner + collaborators), for the aggregated view (§24.3).
     *
     * @param reportId the report id.
     * @return the report's assignment DTOs (possibly empty).
     */
    @Transactional(readOnly = true)
    public java.util.List<ResponderAssignmentDto> listAssignments(UUID reportId) {
        return assignmentRepository.findByReportId(reportId).stream()
                .map(mapper::toAssignmentDto)
                .toList();
    }

    // ---------------------------------------------------------------------------------------------
    // Responder-side case lifecycle (D21) — drives reporting's state machine via its published command
    // port (sync responders → reporting, ADR-0013 §4a). These are thin pass-throughs: reporting owns the
    // §12.1 transition rules, so an illegal transition surfaces as its typed CONFLICT unchanged here.
    // ---------------------------------------------------------------------------------------------

    /**
     * Assigns a responder to a report and drives it {@code → ASSIGNED} (D21). The OWNER
     * {@link ResponderAssignment} is created via {@link #assignResponder} (single-OWNER guard); this then
     * records the assignment on the report and transitions its lifecycle through reporting's state machine.
     *
     * @param reportId          the report to assign (validated to exist by reporting's port).
     * @param responderPublicId the responder taking the case.
     * @param actorPublicId     the acting agent's account public id (timeline attribution).
     * @return reporting's updated {@link ReportDto}.
     */
    public ReportDto startCase(UUID reportId, UUID responderPublicId, UUID actorPublicId) {
        return reportLifecycleApi.assign(reportId, responderPublicId, actorPublicId);
    }

    /**
     * Drives an assigned report {@code → IN_PROGRESS} (§12.1).
     *
     * @param reportId      the report to start.
     * @param actorPublicId the acting agent's account public id.
     * @return reporting's updated {@link ReportDto}.
     */
    public ReportDto beginWork(UUID reportId, UUID actorPublicId) {
        return reportLifecycleApi.start(reportId, actorPublicId);
    }

    /**
     * Resolves a report with the required note ({@code → RESOLVED}, US-3.4).
     *
     * @param reportId       the report to resolve.
     * @param actorPublicId  the acting agent's account public id.
     * @param resolutionNote the required resolution note.
     * @return reporting's updated {@link ReportDto}.
     */
    public ReportDto resolveCase(UUID reportId, UUID actorPublicId, String resolutionNote) {
        return reportLifecycleApi.resolve(reportId, actorPublicId, resolutionNote);
    }

    /**
     * Escalates a report to a supervisor ({@code → ESCALATED}; stays active, §12.1).
     *
     * @param reportId      the report to escalate.
     * @param actorPublicId the acting agent's account public id.
     * @param reason        optional escalation reason for the timeline.
     * @return reporting's updated {@link ReportDto}.
     */
    public ReportDto escalateCase(UUID reportId, UUID actorPublicId, String reason) {
        return reportLifecycleApi.escalate(reportId, actorPublicId, reason);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Loads an organisation by public id or throws a localised not-found. */
    private Organisation requireOrganisation(UUID publicId) {
        return organisationRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("responders.organisation.notFound", publicId));
    }

    /** Loads a responder by public id or throws a localised not-found. */
    private Responder requireResponder(UUID publicId) {
        return responderRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("responders.responder.notFound", publicId));
    }

    /**
     * Validates every supplied reporting-category id exists via reporting's published
     * {@link IssueCategoryQueryApi} (sync {@code responders → reporting}, ADR-0013 §4a). A {@code null}/empty
     * list is a no-op (a responder/rule may start with no categories). An unknown id throws
     * {@link ResourceNotFoundException} (NOT_FOUND) — so responders never reference a non-existent category.
     *
     * @param categoryIds the reporting-category ids to validate, or {@code null}.
     */
    private void validateCategories(java.util.List<UUID> categoryIds) {
        if (categoryIds == null) {
            return;
        }
        for (UUID categoryId : categoryIds) {
            issueCategoryQueryApi.requireCategory(categoryId);
        }
    }
}
