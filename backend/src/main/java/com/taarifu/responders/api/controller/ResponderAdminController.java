package com.taarifu.responders.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.reporting.api.dto.ReportDto;
import com.taarifu.responders.api.dto.AssignCaseRequest;
import com.taarifu.responders.api.dto.CreateAssignmentRequest;
import com.taarifu.responders.api.dto.EscalateCaseRequest;
import com.taarifu.responders.api.dto.ResolveCaseRequest;
import com.taarifu.responders.api.dto.CreateOrganisationRequest;
import com.taarifu.responders.api.dto.CreateResponderRequest;
import com.taarifu.responders.api.dto.CreateRoutingRuleRequest;
import com.taarifu.responders.api.dto.OrganisationDto;
import com.taarifu.responders.api.dto.ResponderAssignmentDto;
import com.taarifu.responders.api.dto.ResponderDto;
import com.taarifu.responders.api.dto.RoutingRuleDto;
import com.taarifu.responders.api.dto.UpdateOrganisationRequest;
import com.taarifu.responders.api.dto.UpdateResponderRequest;
import com.taarifu.responders.api.dto.VerifyOrganisationRequest;
import com.taarifu.responders.application.service.ResponderAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin/Moderator REST surface for managing the responder directory — organisation/responder CRUD,
 * verification, routing rules, and report assignments (PRD §24, D20/D21).
 *
 * <p>Responsibility: the thin HTTP layer over {@link ResponderAdminService}. It validates input
 * ({@code @Valid}), delegates, and wraps results in the single {@link ApiResponse} envelope. No
 * business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p>WHY {@code @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")} on every method (deny-by-
 * default, ARCHITECTURE.md §6.2): provider onboarding and verification are privileged, audited actions
 * (§24.4 — "providers are verified before going live ... impersonation guarded"). Stating the gate at
 * the method makes each admin surface explicitly authorised, never merely "authenticated-only" (the
 * legacy gap, PRD §7.1). Verification is a <b>dedicated</b> endpoint so it is separately auditable from
 * routine edits. Tightening to a scoped/B2B provider-admin role (RESPONDER_ADMIN) for a provider editing
 * its own org is a Phase-2 refinement (§24.4, §24.6); MVP keeps directory administration central to
 * Moderator/Admin.</p>
 *
 * <p>This is the {@code responders} module's mounting point under {@code /responders/admin} (context-path
 * {@code /api/v1}); ids are public {@code UUID}s.</p>
 */
@RestController
@RequestMapping("/responders/admin")
@Tag(name = "Responder Administration",
        description = "Moderator/Admin CRUD, verification, routing, and assignment for responders.")
public class ResponderAdminController {

    private final ResponderAdminService admin;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param admin        the responder administration service.
     * @param responses    envelope builder.
     * @param pageRequests safe {@link Pageable} factory.
     * @param pageMapper   {@code Page}→{@code PageMeta} adapter.
     */
    public ResponderAdminController(ResponderAdminService admin,
                                    ResponseFactory responses,
                                    PageRequestFactory pageRequests,
                                    PageMapper pageMapper) {
        this.admin = admin;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    // ----------------------------------- Organisations ------------------------------------------

    /**
     * Registers a new organisation (created PENDING + unverified).
     *
     * @param request validated creation input.
     * @return {@code 201} + the created {@link OrganisationDto}.
     */
    @PostMapping("/organisations")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")
    @Operation(summary = "Create a responder organisation")
    public ResponseEntity<ApiResponse<OrganisationDto>> createOrganisation(
            @Valid @RequestBody CreateOrganisationRequest request) {
        OrganisationDto created = admin.createOrganisation(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }

    /**
     * Lists all organisations (any status), paged (admin view).
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression.
     * @return a paged envelope of {@link OrganisationDto}.
     */
    @GetMapping("/organisations")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")
    @Operation(summary = "List all organisations (admin)")
    public ApiResponse<List<OrganisationDto>> listOrganisations(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<OrganisationDto> result = admin.listAllOrganisations(pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Updates an organisation's mutable fields (not verification).
     *
     * @param organisationId organisation public id.
     * @param request        validated update input.
     * @return {@code 200} + the updated {@link OrganisationDto}.
     */
    @PutMapping("/organisations/{organisationId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")
    @Operation(summary = "Update an organisation")
    public ApiResponse<OrganisationDto> updateOrganisation(
            @PathVariable UUID organisationId,
            @Valid @RequestBody UpdateOrganisationRequest request) {
        return responses.ok(admin.updateOrganisation(organisationId, request));
    }

    /**
     * Verifies or un-verifies an organisation (the §24.4 go-live gate).
     *
     * @param organisationId organisation public id.
     * @param request        the new verification state.
     * @return {@code 200} + the updated {@link OrganisationDto}.
     */
    @PostMapping("/organisations/{organisationId}/verification")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")
    @Operation(summary = "Verify or un-verify an organisation")
    public ApiResponse<OrganisationDto> verifyOrganisation(
            @PathVariable UUID organisationId,
            @Valid @RequestBody VerifyOrganisationRequest request) {
        return responses.ok(admin.setOrganisationVerified(organisationId, request.verified()));
    }

    // ------------------------------------- Responders -------------------------------------------

    /**
     * Creates a responder capability under an organisation.
     *
     * @param organisationId owning organisation public id.
     * @param request        validated creation input.
     * @return {@code 201} + the created {@link ResponderDto}.
     */
    @PostMapping("/organisations/{organisationId}/responders")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")
    @Operation(summary = "Create a responder capability")
    public ResponseEntity<ApiResponse<ResponderDto>> createResponder(
            @PathVariable UUID organisationId,
            @Valid @RequestBody CreateResponderRequest request) {
        ResponderDto created = admin.createResponder(organisationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }

    /**
     * Lists the responders of an organisation (any status), paged (admin view).
     *
     * @param organisationId owning organisation public id.
     * @param page           zero-based page index.
     * @param size           page size (capped at 100).
     * @param sort           sort expression.
     * @return a paged envelope of {@link ResponderDto}.
     */
    @GetMapping("/organisations/{organisationId}/responders")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")
    @Operation(summary = "List an organisation's responders (admin)")
    public ApiResponse<List<ResponderDto>> listResponders(
            @PathVariable UUID organisationId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<ResponderDto> result = admin.listRespondersOfOrganisation(organisationId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Updates a responder capability's configuration.
     *
     * @param responderId responder public id.
     * @param request     validated update input.
     * @return {@code 200} + the updated {@link ResponderDto}.
     */
    @PutMapping("/responders/{responderId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")
    @Operation(summary = "Update a responder capability")
    public ApiResponse<ResponderDto> updateResponder(
            @PathVariable UUID responderId,
            @Valid @RequestBody UpdateResponderRequest request) {
        return responses.ok(admin.updateResponder(responderId, request));
    }

    // ------------------------------------ Routing rules -----------------------------------------

    /**
     * Creates a routing rule (category → responder kind/sector).
     *
     * @param request validated creation input.
     * @return {@code 201} + the created {@link RoutingRuleDto}.
     */
    @PostMapping("/routing-rules")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")
    @Operation(summary = "Create a routing rule")
    public ResponseEntity<ApiResponse<RoutingRuleDto>> createRoutingRule(
            @Valid @RequestBody CreateRoutingRuleRequest request) {
        RoutingRuleDto created = admin.createRoutingRule(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }

    /**
     * Lists all routing rules, paged (admin view).
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression.
     * @return a paged envelope of {@link RoutingRuleDto}.
     */
    @GetMapping("/routing-rules")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")
    @Operation(summary = "List routing rules (admin)")
    public ApiResponse<List<RoutingRuleDto>> listRoutingRules(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<RoutingRuleDto> result = admin.listRoutingRules(pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    // -------------------------------------- Assignments -----------------------------------------

    /**
     * Assigns a responder to a report as OWNER or COLLABORATOR (single-OWNER enforced, §24.3).
     *
     * @param reportId the report id (loose reference; reporting built in parallel).
     * @param request  validated assignment input.
     * @return {@code 201} + the created {@link ResponderAssignmentDto}.
     */
    @PostMapping("/reports/{reportId}/responder-assignments")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")
    @Operation(summary = "Assign a responder to a report (owner/collaborator)")
    public ResponseEntity<ApiResponse<ResponderAssignmentDto>> assignResponder(
            @PathVariable UUID reportId,
            @Valid @RequestBody CreateAssignmentRequest request) {
        ResponderAssignmentDto created =
                admin.assignResponder(reportId, request, CurrentUser.requirePublicId());
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }

    /**
     * Lists a report's responder assignments (owner + collaborators), for the aggregated view.
     *
     * @param reportId the report id.
     * @return {@code 200} + the list of {@link ResponderAssignmentDto}.
     */
    @GetMapping("/reports/{reportId}/responder-assignments")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR')")
    @Operation(summary = "List a report's responder assignments")
    public ApiResponse<List<ResponderAssignmentDto>> listAssignments(@PathVariable UUID reportId) {
        return responses.ok(admin.listAssignments(reportId));
    }

    // --------------------------- Responder-side case lifecycle (D21) ----------------------------
    // These drive reporting's §12.1 state machine via its published ReportLifecycleApi command port
    // (sync responders → reporting, ADR-0013 §4a). Reporting owns the transition legality; an illegal
    // transition surfaces as its typed CONFLICT. The acting agent is taken from the security context.

    /**
     * Assigns a report to a responder and transitions it to {@code ASSIGNED} (D21).
     *
     * @param reportId the report to assign (existence validated by reporting's port).
     * @param request  the responder taking the case.
     * @return {@code 200} + reporting's updated {@link ReportDto}.
     */
    @PostMapping("/reports/{reportId}/assign")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR') or hasRole('RESPONDER_ADMIN') or hasRole('RESPONDER_AGENT')")
    @Operation(summary = "Assign a report to a responder (→ ASSIGNED)")
    public ApiResponse<ReportDto> assignCase(@PathVariable UUID reportId,
                                             @Valid @RequestBody AssignCaseRequest request) {
        return responses.ok(admin.startCase(reportId, request.responderId(), CurrentUser.requirePublicId()));
    }

    /**
     * Starts work on an assigned report ({@code → IN_PROGRESS}).
     *
     * @param reportId the report to start.
     * @return {@code 200} + reporting's updated {@link ReportDto}.
     */
    @PostMapping("/reports/{reportId}/start")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR') or hasRole('RESPONDER_ADMIN') or hasRole('RESPONDER_AGENT')")
    @Operation(summary = "Start work on a report (→ IN_PROGRESS)")
    public ApiResponse<ReportDto> startCase(@PathVariable UUID reportId) {
        return responses.ok(admin.beginWork(reportId, CurrentUser.requirePublicId()));
    }

    /**
     * Resolves a report with the required resolution note ({@code → RESOLVED}, US-3.4).
     *
     * @param reportId the report to resolve.
     * @param request  the required resolution note.
     * @return {@code 200} + reporting's updated {@link ReportDto}.
     */
    @PostMapping("/reports/{reportId}/resolve")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR') or hasRole('RESPONDER_ADMIN') or hasRole('RESPONDER_AGENT')")
    @Operation(summary = "Resolve a report (→ RESOLVED)")
    public ApiResponse<ReportDto> resolveCase(@PathVariable UUID reportId,
                                              @Valid @RequestBody ResolveCaseRequest request) {
        return responses.ok(
                admin.resolveCase(reportId, CurrentUser.requirePublicId(), request.resolutionNote()));
    }

    /**
     * Escalates a report to a supervisor ({@code → ESCALATED}; stays active).
     *
     * @param reportId the report to escalate.
     * @param request  optional escalation reason.
     * @return {@code 200} + reporting's updated {@link ReportDto}.
     */
    @PostMapping("/reports/{reportId}/escalate")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MODERATOR') or hasRole('RESPONDER_ADMIN') or hasRole('RESPONDER_AGENT')")
    @Operation(summary = "Escalate a report (→ ESCALATED)")
    public ApiResponse<ReportDto> escalateCase(@PathVariable UUID reportId,
                                               @Valid @RequestBody EscalateCaseRequest request) {
        return responses.ok(admin.escalateCase(reportId, CurrentUser.requirePublicId(), request.reason()));
    }
}
