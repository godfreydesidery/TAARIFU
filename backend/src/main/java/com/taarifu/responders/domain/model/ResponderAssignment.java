package com.taarifu.responders.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.responders.domain.model.enums.AssignmentRole;
import com.taarifu.responders.domain.model.enums.AssignmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * Links a {@link Responder} to a report as either the single accountable <b>owner</b> or a
 * <b>collaborator</b> (PRD §24.3, §24.5, D21) — the spine of the multisectoral "one owner +
 * collaborators" model.
 *
 * <p>Responsibility: records which responder works which report, in what {@link AssignmentRole}, at
 * what {@link AssignmentStatus}, when and by whom it was assigned, and the applicable SLA snapshot.
 * The parent report aggregates child statuses and only closes when all assignments resolve (§24.3).</p>
 *
 * <p>WHY the report is referenced by a loose {@code reportId} {@code UUID} and not an FK: the
 * {@code reporting} module (which owns {@code Report}) is built in <b>parallel</b> and must not be
 * imported (ARCHITECTURE.md §3.2; module isolation rule 2). The cross-module link is therefore by
 * id; the FK/wiring to reporting is a later integration step. // TODO(wiring): bind {@code reportId}
 * to the reporting module's {@code Report} (resolve/validate via its public API or an FK once the
 * modules are integrated).</p>
 *
 * <p>WHY the single-OWNER invariant is DB-owned (a partial unique index on
 * {@code (report_id) WHERE role = 'OWNER' AND deleted = false} in the migration), not just enforced in
 * Java: accountability for closure is meaningless if two responders both own the report; the citizen
 * tracks one issue with an aggregated status (§24.3). The DB guarantees "at most one live OWNER per
 * report" so a concurrent double-assign cannot violate it (ARCHITECTURE.md §4.3). The matching
 * "at most one live assignment per (report, responder)" unique index prevents accidental duplicate
 * assignment of the same responder to the same report.</p>
 *
 * <p>WHY {@code assignedByUserPublicId} is a loose {@code UUID}: it references the assigning
 * {@code identity} user, referenced by id per the boundary rules (no cross-module FK).</p>
 */
@Entity
@Table(name = "responder_assignment", indexes = {
        @Index(name = "ix_responder_assignment_report", columnList = "report_id"),
        @Index(name = "ix_responder_assignment_responder", columnList = "responder_id"),
        @Index(name = "ix_responder_assignment_role", columnList = "role"),
        @Index(name = "ix_responder_assignment_status", columnList = "status")
})
@SQLRestriction("deleted = false")
public class ResponderAssignment extends BaseEntity {

    /**
     * The report this assignment belongs to, referenced by id only (no FK — reporting is built in
     * parallel). // TODO(wiring): link to the reporting module's {@code Report}.
     */
    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    /** The assigned responder (FK within this module). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "responder_id", nullable = false)
    private Responder responder;

    /** OWNER (exactly one per report, DB-enforced) or COLLABORATOR (zero-or-more) — PRD §24.3. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private AssignmentRole role;

    /** The responder's progress on its slice (drives parent aggregation + SLA, §24.3, §25.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AssignmentStatus status = AssignmentStatus.PENDING;

    /** When the assignment was made (UTC). Set at creation. */
    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    /**
     * The {@code identity} user (by id) who made the assignment (operator/admin/owner), for audit and
     * conflict-of-interest checks. // TODO(wiring): resolve via identity API. Optional for system routing.
     */
    @Column(name = "assigned_by_user_public_id")
    private UUID assignedByUserPublicId;

    /**
     * A snapshot of the SLA target applicable to this assignment (PRD §24.1/§25.2 — providers may have
     * differing/contractual SLAs). Free text; the structured SLA clock lives in reporting. Optional.
     */
    @Column(name = "sla_policy", length = 1000)
    private String slaPolicy;

    /** JPA requires a no-arg constructor; not for application use. */
    protected ResponderAssignment() {
    }

    /**
     * Creates a new PENDING assignment.
     *
     * @param reportId               the report id (loose reference; reporting built in parallel).
     * @param responder              the responder being assigned.
     * @param role                   OWNER or COLLABORATOR (the single-owner rule is DB-enforced).
     * @param assignedByUserPublicId the assigning user's id (may be {@code null} for system routing).
     * @param assignedAt             the assignment instant (UTC).
     * @return a transient assignment ready to persist.
     */
    public static ResponderAssignment create(UUID reportId, Responder responder, AssignmentRole role,
                                             UUID assignedByUserPublicId, Instant assignedAt) {
        ResponderAssignment a = new ResponderAssignment();
        a.reportId = reportId;
        a.responder = responder;
        a.role = role;
        a.status = AssignmentStatus.PENDING;
        a.assignedByUserPublicId = assignedByUserPublicId;
        a.assignedAt = assignedAt;
        return a;
    }

    /** Advances the assignment status (accept/progress/resolve/reassign/reject). */
    public void changeStatus(AssignmentStatus status) {
        this.status = status;
    }

    /** Changes the assignment role (e.g. promote a collaborator to owner during reassignment). */
    public void changeRole(AssignmentRole role) {
        this.role = role;
    }

    /** Sets the SLA snapshot text for this assignment. */
    public void setSlaPolicy(String slaPolicy) {
        this.slaPolicy = slaPolicy;
    }

    /** @return the report id this assignment belongs to (loose reference). */
    public UUID getReportId() {
        return reportId;
    }

    /** @return the assigned responder. */
    public Responder getResponder() {
        return responder;
    }

    /** @return OWNER or COLLABORATOR. */
    public AssignmentRole getRole() {
        return role;
    }

    /** @return the responder's progress status. */
    public AssignmentStatus getStatus() {
        return status;
    }

    /** @return when the assignment was made (UTC). */
    public Instant getAssignedAt() {
        return assignedAt;
    }

    /** @return the assigning user's public id, or {@code null}. */
    public UUID getAssignedByUserPublicId() {
        return assignedByUserPublicId;
    }

    /** @return the SLA snapshot text, or {@code null}. */
    public String getSlaPolicy() {
        return slaPolicy;
    }
}
