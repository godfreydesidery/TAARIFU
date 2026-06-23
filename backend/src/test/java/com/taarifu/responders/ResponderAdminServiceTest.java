package com.taarifu.responders;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.reporting.api.IssueCategoryQueryApi;
import com.taarifu.reporting.api.ReportLifecycleApi;
import com.taarifu.reporting.api.ReportQueryApi;
import com.taarifu.reporting.api.dto.ReportScopeDto;
import com.taarifu.responders.api.dto.CreateAssignmentRequest;
import com.taarifu.responders.api.dto.ResponderAssignmentDto;
import com.taarifu.responders.application.mapper.ResponderMapper;
import com.taarifu.responders.application.service.ResponderAdminService;
import com.taarifu.responders.domain.model.Organisation;
import com.taarifu.responders.domain.model.Responder;
import com.taarifu.responders.domain.model.ResponderAssignment;
import com.taarifu.responders.domain.model.enums.AssignmentRole;
import com.taarifu.responders.domain.model.enums.CoverageType;
import com.taarifu.responders.domain.model.enums.OrganisationType;
import com.taarifu.responders.domain.model.enums.ResponderType;
import com.taarifu.responders.domain.repository.OrganisationRepository;
import com.taarifu.responders.domain.repository.ResponderAssignmentRepository;
import com.taarifu.responders.domain.repository.ResponderRepository;
import com.taarifu.responders.domain.repository.RoutingRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResponderAdminService} — the multisectoral assignment integrity invariants
 * (PRD §24.3) — with Mockito only (no Docker), so they run in every local build.
 *
 * <p>Responsibility: pins the load-bearing rules the service guards in front of the DB partial-unique
 * indexes: a report has <b>exactly one OWNER</b>, and a responder is assigned to a report <b>at most
 * once</b>. Each negative test fails (the service no longer throws CONFLICT) the moment a guard is
 * removed — proving the guard, not just the happy path (CLAUDE.md §10). The DB indexes are the ultimate
 * authority and are exercised by the Testcontainers integration test.</p>
 */
class ResponderAdminServiceTest {

    private OrganisationRepository organisationRepository;
    private ResponderRepository responderRepository;
    private RoutingRuleRepository routingRuleRepository;
    private ResponderAssignmentRepository assignmentRepository;
    private ReportQueryApi reportQueryApi;
    private IssueCategoryQueryApi issueCategoryQueryApi;
    private ReportLifecycleApi reportLifecycleApi;
    private ScopeGuard scopeGuard;
    private AuditEventService audit;
    private ResponderAdminService service;

    private final UUID reportId = UUID.randomUUID();
    private final UUID responderPublicId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID reportWardId = UUID.randomUUID();
    private final UUID reportCategoryId = UUID.randomUUID();
    private Responder responder;

    @BeforeEach
    void setUp() {
        organisationRepository = mock(OrganisationRepository.class);
        responderRepository = mock(ResponderRepository.class);
        routingRuleRepository = mock(RoutingRuleRepository.class);
        assignmentRepository = mock(ResponderAssignmentRepository.class);
        reportQueryApi = mock(ReportQueryApi.class);
        issueCategoryQueryApi = mock(IssueCategoryQueryApi.class);
        reportLifecycleApi = mock(ReportLifecycleApi.class);
        scopeGuard = mock(ScopeGuard.class);
        audit = mock(AuditEventService.class);
        ClockPort clock = () -> Instant.parse("2026-06-23T09:00:00Z");
        service = new ResponderAdminService(organisationRepository, responderRepository,
                routingRuleRepository, assignmentRepository, reportQueryApi, issueCategoryQueryApi,
                reportLifecycleApi, scopeGuard, audit, new ResponderMapper(), clock);

        Organisation org = Organisation.create("TANESCO", OrganisationType.PARASTATAL);
        responder = Responder.create(org, "TANESCO — Kilimanjaro", ResponderType.UTILITY,
                CoverageType.NATIONWIDE);
        when(responderRepository.findByPublicId(responderPublicId)).thenReturn(Optional.of(responder));
        // No existing assignments by default; the report exists by default (reporting's port is a no-op).
        when(assignmentRepository.findByReportAndResponder(any(), any())).thenReturn(Optional.empty());
        when(assignmentRepository.findOwner(any(), any())).thenReturn(Optional.empty());
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // R-1 default: the report resolves to a ward+category, and the agent is in scope on both axes
        // (lenient — plain mock()). Negative tests below override these to drive an out-of-scope denial.
        lenient().when(reportQueryApi.scopeOf(reportId))
                .thenReturn(new ReportScopeDto(reportWardId, reportCategoryId));
        lenient().when(scopeGuard.canActOnArea(any())).thenReturn(true);
        lenient().when(scopeGuard.canActOnCategory(any())).thenReturn(true);
    }

    @Test
    void assign_firstOwner_succeeds() {
        CreateAssignmentRequest req =
                new CreateAssignmentRequest(responderPublicId, AssignmentRole.OWNER, null);

        ResponderAssignmentDto dto = service.assignResponder(reportId, req, actor);

        assertThat(dto.role()).isEqualTo("OWNER");
        assertThat(dto.reportId()).isEqualTo(reportId);
        assertThat(dto.responderId()).isEqualTo(responder.getPublicId());
        verify(assignmentRepository).save(any(ResponderAssignment.class));
    }

    @Test
    void assign_secondOwner_isConflict() {
        // A report already has an OWNER.
        ResponderAssignment existingOwner = ResponderAssignment.create(
                reportId, responder, AssignmentRole.OWNER, actor, Instant.now());
        when(assignmentRepository.findOwner(reportId, AssignmentRole.OWNER))
                .thenReturn(Optional.of(existingOwner));
        // The second OWNER is a different responder (so the duplicate guard does not trip first).
        UUID otherResponderId = UUID.randomUUID();
        Responder other = Responder.create(
                Organisation.create("DAWASA", OrganisationType.PARASTATAL),
                "DAWASA", ResponderType.UTILITY, CoverageType.NATIONWIDE);
        when(responderRepository.findByPublicId(otherResponderId)).thenReturn(Optional.of(other));

        CreateAssignmentRequest req =
                new CreateAssignmentRequest(otherResponderId, AssignmentRole.OWNER, null);

        assertThatThrownBy(() -> service.assignResponder(reportId, req, actor))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void assign_collaboratorAlongsideOwner_succeeds() {
        // An OWNER exists, but a COLLABORATOR for a different responder is allowed (§24.3).
        ResponderAssignment existingOwner = ResponderAssignment.create(
                reportId, responder, AssignmentRole.OWNER, actor, Instant.now());
        when(assignmentRepository.findOwner(reportId, AssignmentRole.OWNER))
                .thenReturn(Optional.of(existingOwner));
        UUID collaboratorId = UUID.randomUUID();
        Responder collaborator = Responder.create(
                Organisation.create("DAWASA", OrganisationType.PARASTATAL),
                "DAWASA", ResponderType.UTILITY, CoverageType.NATIONWIDE);
        when(responderRepository.findByPublicId(collaboratorId)).thenReturn(Optional.of(collaborator));

        CreateAssignmentRequest req =
                new CreateAssignmentRequest(collaboratorId, AssignmentRole.COLLABORATOR, null);

        ResponderAssignmentDto dto = service.assignResponder(reportId, req, actor);

        assertThat(dto.role()).isEqualTo("COLLABORATOR");
        verify(assignmentRepository).save(any());
    }

    @Test
    void assign_sameResponderTwice_isConflict() {
        ResponderAssignment existing = ResponderAssignment.create(
                reportId, responder, AssignmentRole.COLLABORATOR, actor, Instant.now());
        when(assignmentRepository.findByReportAndResponder(reportId, responderPublicId))
                .thenReturn(Optional.of(existing));

        CreateAssignmentRequest req =
                new CreateAssignmentRequest(responderPublicId, AssignmentRole.COLLABORATOR, null);

        assertThatThrownBy(() -> service.assignResponder(reportId, req, actor))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void assign_unknownResponder_isNotFound() {
        UUID missing = UUID.randomUUID();
        when(responderRepository.findByPublicId(missing)).thenReturn(Optional.empty());
        CreateAssignmentRequest req =
                new CreateAssignmentRequest(missing, AssignmentRole.OWNER, null);

        assertThatThrownBy(() -> service.assignResponder(reportId, req, actor))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void assign_unknownReport_isNotFound_viaReportingPort() {
        // Reporting's published port reports the report does not exist → assignment is refused BEFORE any
        // responder/assignment work (sync responders → reporting validation, ADR-0013 §4a / D21).
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("reporting.report.notFound", reportId))
                .when(reportQueryApi).requireExists(reportId);
        CreateAssignmentRequest req =
                new CreateAssignmentRequest(responderPublicId, AssignmentRole.OWNER, null);

        assertThatThrownBy(() -> service.assignResponder(reportId, req, actor))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(assignmentRepository, never()).save(any());
        // The report check runs first — the responder is never even looked up.
        verify(responderRepository, never()).findByPublicId(any());
    }

    @Test
    void lifecycleActions_delegateToReportingPort_drivingTheStateMachine() {
        // The responder-side lifecycle actions are thin pass-throughs to reporting's published command port
        // (sync responders → reporting); reporting owns the §12.1 transitions (D21, ADR-0013 §4a). The R-1
        // scope check passes here (in-scope agent, stubbed in setUp), so each delegates to reporting.
        service.startCase(reportId, responderPublicId, actor);
        verify(reportLifecycleApi).assign(reportId, responderPublicId, actor);

        service.beginWork(reportId, actor);
        verify(reportLifecycleApi).start(reportId, actor);

        service.resolveCase(reportId, actor, "fixed");
        verify(reportLifecycleApi).resolve(reportId, actor, "fixed");

        service.escalateCase(reportId, actor, "urgent");
        verify(reportLifecycleApi).escalate(reportId, actor, "urgent");
    }

    @Test
    void lifecycle_inScopeAgent_isAllowed_andGatesOnReportWardAndCategory() {
        // R-1: an agent whose live RoleAssignment covers the report's ward AND category may drive the
        // lifecycle. The scope is resolved from reporting's published port (ward + category public ids).
        service.resolveCase(reportId, actor, "fixed");

        // The scope was resolved off the report and BOTH axes were checked (area ∧ category).
        verify(reportQueryApi).scopeOf(reportId);
        verify(scopeGuard).canActOnArea(reportWardId);
        verify(scopeGuard).canActOnCategory(reportCategoryId);
        verify(reportLifecycleApi).resolve(reportId, actor, "fixed");
    }

    @Test
    void lifecycle_agentOutsideReportArea_isOutOfScope_403_andNeverTransitions() {
        // R-1: an agent whose RoleAssignment does NOT cover the report's ward is denied OUT_OF_SCOPE — the
        // report's lifecycle is never driven. This test fails (no exception) the moment the area gate is
        // removed from the lifecycle path — proving the guard, not the happy path (CLAUDE.md §10).
        when(scopeGuard.canActOnArea(reportWardId)).thenReturn(false);

        assertThatThrownBy(() -> service.escalateCase(reportId, actor, "urgent"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.OUT_OF_SCOPE);

        // The out-of-scope agent never reaches reporting's state machine, and the denial is audited.
        verify(reportLifecycleApi, never()).escalate(any(), any(), any());
        verify(audit).record(any());
    }

    @Test
    void lifecycle_agentOutsideReportCategory_isOutOfScope_403_andNeverTransitions() {
        // R-1: in-area but NOT handling the report's category → still OUT_OF_SCOPE (both axes required).
        when(scopeGuard.canActOnCategory(reportCategoryId)).thenReturn(false);

        assertThatThrownBy(() -> service.startCase(reportId, responderPublicId, actor))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.OUT_OF_SCOPE);

        verify(reportLifecycleApi, never()).assign(any(), any(), any());
        verify(audit).record(any());
    }

    @Test
    void lifecycle_unknownReport_isNotFound_viaReportingScopePort() {
        // R-1: the scope resolution runs first; an unknown report is NOT_FOUND before any transition.
        UUID missing = UUID.randomUUID();
        when(reportQueryApi.scopeOf(missing))
                .thenThrow(new ResourceNotFoundException("reporting.report.notFound", missing));

        assertThatThrownBy(() -> service.beginWork(missing, actor))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(reportLifecycleApi, never()).start(any(), any());
    }
}
