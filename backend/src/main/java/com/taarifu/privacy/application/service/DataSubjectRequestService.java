package com.taarifu.privacy.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.identity.api.UserAdminQueryApi;
import com.taarifu.identity.api.dto.UserRoleGrant;
import com.taarifu.privacy.api.dto.DsrDto;
import com.taarifu.privacy.api.event.ErasureRequested;
import com.taarifu.privacy.domain.model.DataSubjectRequest;
import com.taarifu.privacy.domain.model.enums.DsrStatus;
import com.taarifu.privacy.domain.model.enums.DsrType;
import com.taarifu.privacy.domain.repository.DataSubjectRequestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The data-subject-request intake + operator workflow (PRD §18 PDPA, §25.1, UC-A17/UC-S09; ADR-0016 §3/§5/§7).
 *
 * <p>Responsibility: open and track ACCESS / ERASURE requests, drive the SLA lifecycle (acknowledge ≤72h,
 * complete ≤30 days — §25.1), apply legal hold, and — for erasure — publish the {@code ERASURE_REQUESTED}
 * outbox event <b>in the same transaction</b> as the request insert so the fan-out can never be lost
 * (ADR-0014 atomicity). Every state-changing step is audited (references/codes only — no PII).</p>
 *
 * <p><b>Active-role erasure constraint (PRD note ᵇ, ADR-0016 §6):</b> a holder of an <b>active</b> staff or
 * representative role cannot self-erase while the role is active — the role must first be
 * revoked/transitioned (Rep→FORMER) by an Admin, preserving the accountability/civic history. The check reads
 * live grants through the published {@code identity.api.UserAdminQueryApi} (no identity-internals reach-in,
 * ADR-0013) and throws {@code CONFLICT} if an active staff/rep role is held.</p>
 *
 * <p><b>Idempotent self-requests:</b> re-requesting the same open right returns the existing request rather
 * than spawning a duplicate erasure fan-out.</p>
 */
@Service
public class DataSubjectRequestService {

    /** Roles whose <b>active</b> grant blocks self-erasure (accountability must survive — note ᵇ, D16/§6.4). */
    private static final Set<String> ROLES_BLOCKING_SELF_ERASURE = Set.of(
            "MODERATOR", "ADMIN", "ROOT", "REPRESENTATIVE", "RESPONDER_AGENT", "RESPONDER_ADMIN",
            "AREA_OFFICIAL", "ORGANIZATION_ADMIN");

    /** The non-terminal statuses that make a request "open" (so a repeat self-request is idempotent). */
    private static final List<DsrStatus> OPEN_STATUSES = List.of(
            DsrStatus.RECEIVED, DsrStatus.ACKNOWLEDGED, DsrStatus.IN_PROGRESS, DsrStatus.ON_HOLD);

    /** PRD §25.1 completion SLA: erasure/export complete within 30 days. */
    private static final Duration COMPLETE_WITHIN = Duration.ofDays(30);

    private final DataSubjectRequestRepository requestRepository;
    private final OutboxWriter outboxWriter;
    private final UserAdminQueryApi userQuery;
    private final AuditEventService audit;
    private final ClockPort clock;

    /**
     * @param requestRepository the DSR store.
     * @param outboxWriter      the transactional outbox writer (publishes {@code ERASURE_REQUESTED} atomically).
     * @param userQuery         identity's published read port — checks live role grants (active-role
     *                          constraint) without reaching into identity internals (ADR-0013).
     * @param audit             append-only audit writer (records receipt + erasure-request — L-1).
     * @param clock             time source for receipt/SLA stamps (testable).
     */
    public DataSubjectRequestService(DataSubjectRequestRepository requestRepository,
                                     OutboxWriter outboxWriter,
                                     UserAdminQueryApi userQuery,
                                     AuditEventService audit,
                                     ClockPort clock) {
        this.requestRepository = requestRepository;
        this.outboxWriter = outboxWriter;
        this.userQuery = userQuery;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Opens an ACCESS (export) request for the caller (idempotent on an already-open ACCESS request).
     *
     * @param subjectPublicId the authenticated caller's account public id.
     * @return the (new or existing open) request.
     */
    @Transactional
    public DsrDto requestAccess(UUID subjectPublicId) {
        DataSubjectRequest existing = firstOpen(subjectPublicId, DsrType.ACCESS);
        if (existing != null) {
            return toDto(existing);
        }
        DataSubjectRequest request = requestRepository.save(
                DataSubjectRequest.open(subjectPublicId, DsrType.ACCESS, clock.now(), COMPLETE_WITHIN));
        audit.record(AuditEvent.Builder
                .of(AuditEventType.PRIVACY_DSR_RECEIVED, AuditOutcome.SUCCESS)
                .actor(subjectPublicId).subject(subjectPublicId).reason(DsrType.ACCESS.name())
                .build());
        return toDto(request);
    }

    /**
     * Opens an ERASURE request for the caller and publishes the {@code ERASURE_REQUESTED} fan-out event in the
     * same transaction (ADR-0014 atomicity). Blocked if the caller holds an active staff/rep role (note ᵇ).
     *
     * @param subjectPublicId the authenticated caller's account public id.
     * @return the (new or existing open) erasure request.
     * @throws ApiException {@link ErrorCode#CONFLICT} if an active staff/representative role is held (the role
     *                      must be revoked/transitioned by an Admin first — accountability is preserved).
     */
    @Transactional
    public DsrDto requestErasure(UUID subjectPublicId) {
        if (holdsActiveStaffOrRepRole(subjectPublicId)) {
            // Note ᵇ: an active-role holder cannot erase their accountability trail by self-deletion.
            throw new ApiException(ErrorCode.CONFLICT);
        }
        DataSubjectRequest existing = firstOpen(subjectPublicId, DsrType.ERASURE);
        if (existing != null) {
            return toDto(existing);
        }

        DataSubjectRequest request = requestRepository.save(
                DataSubjectRequest.open(subjectPublicId, DsrType.ERASURE, clock.now(), COMPLETE_WITHIN));

        // Publish the fan-out event IN THIS TRANSACTION (atomic with the DSR insert — a crash can never leave
        // the request recorded but the severing un-triggered). Payload = ids ONLY, never PII (ADR-0014 §1).
        outboxWriter.append(EventEnvelope.of(
                ErasureRequested.EVENT_TYPE,
                ErasureRequested.AGGREGATE_TYPE,
                request.getPublicId(),
                new ErasureRequested(subjectPublicId, request.getPublicId()),
                clock.now()));

        audit.record(AuditEvent.Builder
                .of(AuditEventType.PRIVACY_ERASURE_REQUESTED, AuditOutcome.SUCCESS)
                .actor(subjectPublicId).subject(subjectPublicId).reason(DsrType.ERASURE.name())
                .build());
        return toDto(request);
    }

    /**
     * Lists the caller's own requests' status (self-service tracking).
     *
     * @param subjectPublicId the authenticated caller's account public id.
     * @return the caller's open requests (never {@code null}).
     */
    @Transactional(readOnly = true)
    public List<DsrDto> myRequests(UUID subjectPublicId) {
        List<DataSubjectRequest> access = requestRepository
                .findBySubjectPublicIdAndTypeAndStatusIn(subjectPublicId, DsrType.ACCESS, OPEN_STATUSES);
        List<DataSubjectRequest> erasure = requestRepository
                .findBySubjectPublicIdAndTypeAndStatusIn(subjectPublicId, DsrType.ERASURE, OPEN_STATUSES);
        return java.util.stream.Stream.concat(access.stream(), erasure.stream())
                .map(DataSubjectRequestService::toDto)
                .toList();
    }

    /**
     * The ADMIN/ROOT operator queue: open requests, oldest-due first.
     *
     * @param pageable pagination + sort (the controller normalises/caps).
     * @return a page of open requests.
     */
    @Transactional(readOnly = true)
    public Page<DsrDto> queue(Pageable pageable) {
        return requestRepository.findByStatusIn(OPEN_STATUSES, pageable).map(DataSubjectRequestService::toDto);
    }

    /**
     * Acknowledges a request to the subject (PRD §25.1 ≤72h obligation). ADMIN/ROOT only (controller-gated).
     *
     * @param actorPublicId the acting operator's public id.
     * @param requestPublicId the request to acknowledge.
     * @return the updated request.
     */
    @Transactional
    public DsrDto acknowledge(UUID actorPublicId, UUID requestPublicId) {
        DataSubjectRequest request = require(requestPublicId);
        request.acknowledge(clock.now());
        return toDto(request);
    }

    /**
     * Places a request under legal hold — an item under investigation is exempt from erasure until released
     * (§25.1). ADMIN/ROOT only.
     *
     * @param actorPublicId the acting operator.
     * @param requestPublicId the request to hold.
     * @param reasonCode    the machine hold reason (never PII).
     * @return the updated request.
     */
    @Transactional
    public DsrDto placeOnHold(UUID actorPublicId, UUID requestPublicId, String reasonCode) {
        DataSubjectRequest request = require(requestPublicId);
        request.placeOnHold(reasonCode);
        return toDto(request);
    }

    /**
     * Marks a request fully fulfilled/closed (export delivered, or erasure handlers completed). ADMIN/ROOT only.
     *
     * @param actorPublicId the acting operator.
     * @param requestPublicId the request to complete.
     * @return the updated request.
     */
    @Transactional
    public DsrDto complete(UUID actorPublicId, UUID requestPublicId) {
        DataSubjectRequest request = require(requestPublicId);
        request.complete(clock.now());
        return toDto(request);
    }

    /** Returns the caller's first still-open request of a type, or {@code null} (idempotency helper). */
    private DataSubjectRequest firstOpen(UUID subjectPublicId, DsrType type) {
        List<DataSubjectRequest> open = requestRepository
                .findBySubjectPublicIdAndTypeAndStatusIn(subjectPublicId, type, OPEN_STATUSES);
        return open.isEmpty() ? null : open.get(0);
    }

    /**
     * Whether the subject holds an <b>active</b> staff or representative role (note ᵇ). Reads live grants
     * through identity's published query port — deny-by-default: if the account or its grants cannot be read,
     * treat as blocking is NOT applied (a missing account is a NOT_FOUND from the port, surfaced as-is).
     */
    private boolean holdsActiveStaffOrRepRole(UUID subjectPublicId) {
        List<UserRoleGrant> roles = userQuery.getUser(subjectPublicId).roles();
        return roles.stream().anyMatch(g ->
                "ACTIVE".equals(g.status()) && ROLES_BLOCKING_SELF_ERASURE.contains(g.roleName()));
    }

    /** Loads a request by public id or throws NOT_FOUND. */
    private DataSubjectRequest require(UUID requestPublicId) {
        return requestRepository.findByPublicId(requestPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** Maps a request entity to its boundary DTO. */
    private static DsrDto toDto(DataSubjectRequest r) {
        return new DsrDto(r.getPublicId(), r.getSubjectPublicId(), r.getType().name(), r.getStatus().name(),
                r.getRequestedAt(), r.getAcknowledgedAt(), r.getCompletedAt(), r.getDueAt(),
                r.isLegalHold(), r.getReasonCode());
    }
}
