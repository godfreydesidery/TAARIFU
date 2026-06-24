package com.taarifu.responders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.reporting.api.event.ReportEventTypes;
import com.taarifu.reporting.api.event.ReportRouted;
import com.taarifu.responders.api.event.ResponderAssignedEvent;
import com.taarifu.responders.api.event.ResponderEventTypes;
import com.taarifu.responders.application.service.RoutingHandler;
import com.taarifu.responders.domain.model.Organisation;
import com.taarifu.responders.domain.model.Responder;
import com.taarifu.responders.domain.model.ResponderAssignment;
import com.taarifu.responders.domain.model.RoutingRule;
import com.taarifu.responders.domain.model.enums.AssignmentRole;
import com.taarifu.responders.domain.model.enums.CoverageType;
import com.taarifu.responders.domain.model.enums.OrganisationStatus;
import com.taarifu.responders.domain.model.enums.OrganisationType;
import com.taarifu.responders.domain.model.enums.ProviderSelectionMode;
import com.taarifu.responders.domain.model.enums.ResponderStatus;
import com.taarifu.responders.domain.model.enums.ResponderType;
import com.taarifu.responders.domain.repository.ResponderAssignmentRepository;
import com.taarifu.responders.domain.repository.ResponderRepository;
import com.taarifu.responders.domain.repository.RoutingRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RoutingHandler} — the {@code REPORT_ROUTED} → OWNER {@code ResponderAssignment}
 * outbox handler (PRD §24.2/§24.3, D21; ADR-0014 §5b) — Mockito only (no Docker, no Spring).
 *
 * <p>Responsibility: pins the load-bearing routing invariants a reviewer must never see silently regress:</p>
 * <ul>
 *   <li>a routed report gets <b>exactly one OWNER</b> assignment, resolved from the rule's preferred
 *       responder or the directory by type+category+coverage;</li>
 *   <li><b>idempotency</b> — a redelivery is a no-op when an OWNER already exists, and a concurrent
 *       single-OWNER constraint violation is swallowed as "already routed" (never a double-OWNER, never a
 *       DLQ);</li>
 *   <li>a config gap (no rule / no eligible responder) is a <b>no-op success</b>, not a failure;</li>
 *   <li>on a new OWNER, a {@code RESPONDER_ASSIGNED} back-event is appended to close the loop async;</li>
 *   <li>the <b>integrity fence (D18)</b> — routing never reads the token ledger (proven structurally: no
 *       tokens collaborator is wired into the handler at all).</li>
 * </ul>
 */
class RoutingHandlerTest {

    private RoutingRuleRepository routingRuleRepository;
    private ResponderRepository responderRepository;
    private ResponderAssignmentRepository assignmentRepository;
    private OutboxWriter outboxWriter;
    private RoutingHandler handler;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final Instant now = Instant.parse("2026-06-23T09:00:00Z");

    private final UUID reportId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();
    private final UUID wardId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        routingRuleRepository = mock(RoutingRuleRepository.class);
        responderRepository = mock(ResponderRepository.class);
        assignmentRepository = mock(ResponderAssignmentRepository.class);
        outboxWriter = mock(OutboxWriter.class);
        ClockPort clock = () -> now;
        handler = new RoutingHandler(routingRuleRepository, responderRepository, assignmentRepository,
                outboxWriter, objectMapper, clock);

        // Default: no existing OWNER; save echoes the entity (with a publicId assigned, as on persist).
        when(assignmentRepository.findOwner(any(), eq(AssignmentRole.OWNER))).thenReturn(Optional.empty());
        when(assignmentRepository.save(any())).thenAnswer(inv -> {
            ResponderAssignment a = inv.getArgument(0);
            assignPublicId(a, UUID.randomUUID());
            return a;
        });
    }

    @Test
    void handle_registersForReportRoutedType() {
        assertThat(handler.handledEventTypes()).containsExactly(ReportEventTypes.REPORT_ROUTED);
    }

    @Test
    void handle_resolvesPreferredResponder_createsOwner_andEmitsBackEvent() {
        Responder preferred = activeResponder(ResponderType.UTILITY, CoverageType.NATIONWIDE, categoryId);
        UUID responderId = preferred.getPublicId();
        RoutingRule rule = ruleWithPreferred(categoryId, ResponderType.UTILITY, preferred);
        when(routingRuleRepository.findActiveByCategoryOrderByPriority(categoryId)).thenReturn(List.of(rule));

        handler.handle(routedEnvelope());

        // Exactly one OWNER assignment is created for the routed report, for the preferred responder.
        ArgumentCaptor<ResponderAssignment> saved = ArgumentCaptor.forClass(ResponderAssignment.class);
        verify(assignmentRepository).save(saved.capture());
        assertThat(saved.getValue().getReportId()).isEqualTo(reportId);
        assertThat(saved.getValue().getRole()).isEqualTo(AssignmentRole.OWNER);
        assertThat(saved.getValue().getResponder().getPublicId()).isEqualTo(responderId);
        // System routing → no operator attribution.
        assertThat(saved.getValue().getAssignedByUserPublicId()).isNull();

        // The loop is closed async: a RESPONDER_ASSIGNED back-event is appended (ids/enums only).
        ResponderAssignedEvent back = captureBackEvent();
        assertThat(back.reportId()).isEqualTo(reportId);
        assertThat(back.responderId()).isEqualTo(responderId);
        assertThat(back.role()).isEqualTo(AssignmentRole.OWNER);
    }

    @Test
    void handle_fallsBackToDirectory_whenNoPreferred() {
        // Rule has no preferred responder; the directory yields an ACTIVE responder of the type covering the ward.
        RoutingRule rule = RoutingRule.create(categoryId, ResponderType.GOVERNMENT_AGENCY,
                ProviderSelectionMode.AUTO_BY_AREA);
        when(routingRuleRepository.findActiveByCategoryOrderByPriority(categoryId)).thenReturn(List.of(rule));
        Responder candidate = activeResponder(ResponderType.GOVERNMENT_AGENCY, CoverageType.AREAS, categoryId);
        candidate.setCoverageAreaIds(Set.of(wardId));
        when(responderRepository.findRoutingCandidates(ResponderType.GOVERNMENT_AGENCY, categoryId))
                .thenReturn(List.of(candidate));

        handler.handle(routedEnvelope());

        ArgumentCaptor<ResponderAssignment> saved = ArgumentCaptor.forClass(ResponderAssignment.class);
        verify(assignmentRepository).save(saved.capture());
        assertThat(saved.getValue().getResponder().getPublicId()).isEqualTo(candidate.getPublicId());
    }

    @Test
    void handle_skipsCandidateNotCoveringWard() {
        RoutingRule rule = RoutingRule.create(categoryId, ResponderType.GOVERNMENT_AGENCY,
                ProviderSelectionMode.AUTO_BY_AREA);
        when(routingRuleRepository.findActiveByCategoryOrderByPriority(categoryId)).thenReturn(List.of(rule));
        // The only candidate covers a DIFFERENT ward → not eligible → no OWNER, no-op success.
        Responder elsewhere = activeResponder(ResponderType.GOVERNMENT_AGENCY, CoverageType.AREAS, categoryId);
        elsewhere.setCoverageAreaIds(Set.of(UUID.randomUUID()));
        when(responderRepository.findRoutingCandidates(ResponderType.GOVERNMENT_AGENCY, categoryId))
                .thenReturn(List.of(elsewhere));

        handler.handle(routedEnvelope());

        verify(assignmentRepository, never()).save(any());
        verify(outboxWriter, never()).append(any());
    }

    @Test
    void handle_noRule_isNoOpSuccess_notFailure() {
        // A category with no routing rule leaves the report unrouted for manual assignment (§25.2) — and must
        // NOT throw (which would DLQ the event). No assignment, no back-event.
        when(routingRuleRepository.findActiveByCategoryOrderByPriority(categoryId)).thenReturn(List.of());

        handler.handle(routedEnvelope()); // does not throw

        verify(assignmentRepository, never()).save(any());
        verify(outboxWriter, never()).append(any());
    }

    @Test
    void handle_reportAlreadyHasOwner_isIdempotentNoOp() {
        // A redelivery (at-least-once): the report already has a live OWNER → skip without re-resolving or saving.
        Responder existing = activeResponder(ResponderType.UTILITY, CoverageType.NATIONWIDE, categoryId);
        ResponderAssignment owner = ResponderAssignment.create(
                reportId, existing, AssignmentRole.OWNER, null, now);
        when(assignmentRepository.findOwner(reportId, AssignmentRole.OWNER)).thenReturn(Optional.of(owner));

        handler.handle(routedEnvelope());

        verify(routingRuleRepository, never()).findActiveByCategoryOrderByPriority(any());
        verify(assignmentRepository, never()).save(any());
        verify(outboxWriter, never()).append(any());
    }

    @Test
    void handle_concurrentOwnerConstraintViolation_isSwallowedAsNoOp() {
        // A racing redelivery slips past the read-check; the partial-unique index ux_responder_assignment_one_owner
        // rejects the second OWNER. The handler swallows it as "already routed" — never a double-OWNER, never a DLQ,
        // and (critically) it does NOT emit a duplicate RESPONDER_ASSIGNED.
        Responder preferred = activeResponder(ResponderType.UTILITY, CoverageType.NATIONWIDE, categoryId);
        RoutingRule rule = ruleWithPreferred(categoryId, ResponderType.UTILITY, preferred);
        when(routingRuleRepository.findActiveByCategoryOrderByPriority(categoryId)).thenReturn(List.of(rule));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("one owner"))
                .when(assignmentRepository).flush();

        handler.handle(routedEnvelope()); // does not throw

        verify(outboxWriter, never()).append(any());
    }

    // --------------------------------------------------------------------------------------------- helpers

    /** Builds the REPORT_ROUTED envelope as the relay would deliver it (ids-only payload). */
    private EventEnvelope<ReportRouted> routedEnvelope() {
        return new EventEnvelope<>(UUID.randomUUID(), ReportEventTypes.REPORT_ROUTED,
                ReportEventTypes.AGGREGATE_REPORT, reportId,
                new ReportRouted(reportId, categoryId, wardId, now), now);
    }

    /**
     * Captures the RESPONDER_ASSIGNED back-event payload appended to the outbox. On a successful OWNER creation
     * the handler appends TWO events: (1) RESPONDER_ASSIGNED (closes the routing loop) and (2) the analytics
     * CIVIC_ACTIVITY_RECORDED report_routed fact (M15). This filters to the RESPONDER_ASSIGNED one and also
     * asserts the analytics fact carries the routed ward/category dimensions (ids only — no PII).
     */
    private ResponderAssignedEvent captureBackEvent() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<EventEnvelope<?>> captor =
                (ArgumentCaptor<EventEnvelope<?>>) (ArgumentCaptor) ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outboxWriter, org.mockito.Mockito.times(2)).append(captor.capture());

        // ANALYTICS (M15): one of the two appends is the report_routed civic-activity fact, with ward + category.
        EventEnvelope<?> analytics = captor.getAllValues().stream()
                .filter(e -> e.eventType().equals(
                        com.taarifu.analytics.api.event.AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED))
                .findFirst().orElseThrow();
        var fact = (com.taarifu.analytics.api.event.CivicActivityRecorded) analytics.payload();
        assertThat(fact.analyticsEventType())
                .isEqualTo(com.taarifu.analytics.api.event.AnalyticsEventTypes.REPORT_ROUTED);
        assertThat(fact.geoAreaId()).isEqualTo(wardId);
        assertThat(fact.categoryId()).isEqualTo(categoryId);
        assertThat(fact.activeRole()).isEqualTo("SYSTEM");

        EventEnvelope<?> back = captor.getAllValues().stream()
                .filter(e -> e.eventType().equals(ResponderEventTypes.RESPONDER_ASSIGNED))
                .findFirst().orElseThrow();
        assertThat(back.payload()).isInstanceOf(ResponderAssignedEvent.class);
        return (ResponderAssignedEvent) back.payload();
    }

    /** Builds an ACTIVE responder (under an active+verified org) handling the category, with a publicId. */
    private Responder activeResponder(ResponderType type, CoverageType coverage, UUID handledCategory) {
        Organisation org = Organisation.create("ORG", OrganisationType.PARASTATAL);
        org.changeStatus(OrganisationStatus.ACTIVE);
        org.setVerified(true);
        Responder r = Responder.create(org, "Responder", type, coverage);
        r.changeStatus(ResponderStatus.ACTIVE);
        r.setHandledCategoryIds(Set.of(handledCategory));
        assignPublicId(r, UUID.randomUUID());
        return r;
    }

    /** A rule pinning a preferred responder (ProviderSelectionMode is irrelevant to resolution here). */
    private RoutingRule ruleWithPreferred(UUID category, ResponderType type, Responder preferred) {
        RoutingRule rule = RoutingRule.create(category, type, ProviderSelectionMode.AUTO_BY_AREA);
        rule.setPreferredResponder(preferred);
        return rule;
    }

    /** Reflectively assigns {@code BaseEntity.publicId} (production sets it on @PrePersist). */
    private static void assignPublicId(BaseEntity entity, UUID publicId) {
        try {
            Field field = BaseEntity.class.getDeclaredField("publicId");
            field.setAccessible(true);
            field.set(entity, publicId);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to assign test publicId", ex);
        }
    }
}
