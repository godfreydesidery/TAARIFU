package com.taarifu.reporting.api;

import com.taarifu.reporting.api.dto.ReportDto;

import java.util.UUID;

/**
 * The reporting module's <b>public, in-process command port</b> for the responder-side case lifecycle
 * actions that drive the {@code Report} state machine (ADR-0013 §1, §4a; D21, PRD §12.1).
 *
 * <p>Responsibility: expose the four responder transitions — <b>assign</b>, <b>start</b>, <b>resolve</b>,
 * <b>escalate</b> — as a published contract the responders module calls (synchronous
 * {@code responders → reporting}, the ADR's allowed direction). Reporting remains the single owner of its
 * §12.1 state machine: each method routes through {@code ReportService}'s guarded {@code transition}, so an
 * illegal transition is a typed {@code CONFLICT} and every change appends a timeline {@code CaseEvent}. The
 * caller never touches reporting's entities/tables — it sees only public {@code UUID}s and {@link ReportDto}.</p>
 *
 * <p>WHY this is the synchronous {@code responders → reporting} edge (not {@code reporting → responders}):
 * the responder acts on a case it owns and needs the transition applied in its own request; reporting is the
 * authority for the lifecycle. The reverse routing-on-creation direction
 * ({@code reporting → responders}: route a NEW report to an OWNER assignment) is deliberately
 * <b>asynchronous via the outbox</b> ({@code REPORT_ROUTED}), and its report-side back-leg — applying the
 * OWNER pointer + {@code NEW → ASSIGNED} when {@code RESPONDER_ASSIGNED} returns — is the reporting
 * {@code ResponderAssignedHandler} (also async, via {@code ReportService.applySystemAssignment}), so there
 * is no synchronous cycle between the two modules (ADR-0013 §2/§4a; ADR-0014 §5b).</p>
 */
public interface ReportLifecycleApi {

    /**
     * Assigns the report to a responder and transitions it to {@code ASSIGNED} (from {@code NEW},
     * {@code REOPENED}, or a re-assign from an active state — §12.1). Records the assigned responder on the
     * report ({@code Report.assignedResponderId}) and appends a {@code STATUS_CHANGE} timeline event.
     *
     * @param reportPublicId    the report to assign.
     * @param responderPublicId the responder (owner) the report is assigned to.
     * @param actorPublicId     the acting user's account public id (for the timeline/attribution).
     * @return the updated {@link ReportDto}.
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND} if the report is unknown,
     *         {@code CONFLICT} if {@code ASSIGNED} is not reachable from the current state.
     */
    ReportDto assign(UUID reportPublicId, UUID responderPublicId, UUID actorPublicId);

    /**
     * Starts work on an assigned report ({@code ASSIGNED}/{@code ESCALATED} → {@code IN_PROGRESS}, §12.1).
     *
     * @param reportPublicId the report to start.
     * @param actorPublicId  the acting user's account public id.
     * @return the updated {@link ReportDto}.
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND}/{@code CONFLICT} as above.
     */
    ReportDto start(UUID reportPublicId, UUID actorPublicId);

    /**
     * Resolves the report with a required resolution note ({@code → RESOLVED}, US-3.4, §12.1); opens the
     * citizen confirm/dispute window.
     *
     * @param reportPublicId the report to resolve.
     * @param actorPublicId  the acting user's account public id.
     * @param resolutionNote the required resolution note.
     * @return the updated {@link ReportDto}.
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND}/{@code CONFLICT}, or
     *         {@code BAD_REQUEST} if the note is blank.
     */
    ReportDto resolve(UUID reportPublicId, UUID actorPublicId, String resolutionNote);

    /**
     * Escalates the report to a supervisor ({@code → ESCALATED}; stays active, §12.1).
     *
     * @param reportPublicId the report to escalate.
     * @param actorPublicId  the acting user's account public id.
     * @param reason         optional escalation reason recorded on the timeline.
     * @return the updated {@link ReportDto}.
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND}/{@code CONFLICT} as above.
     */
    ReportDto escalate(UUID reportPublicId, UUID actorPublicId, String reason);
}
