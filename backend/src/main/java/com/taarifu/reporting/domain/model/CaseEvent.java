package com.taarifu.reporting.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.reporting.domain.model.enums.CaseEventType;
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

import java.util.UUID;

/**
 * One entry on a report's append-only case timeline (PRD §10 US-3.2/US-3.4, §12.1, M3).
 *
 * <p>Responsibility: records a single thing that happened to a {@link Report} — a status change, an
 * assignment, a comment, an attachment, or an escalation — with its actor and a {@code public}/internal
 * visibility flag. The ordered set of a report's case events is the citizen-facing history (US-3.2) and
 * the operational record (US-3.4 "public vs internal notes").</p>
 *
 * <h3>Privacy &amp; integrity</h3>
 * <ul>
 *   <li><b>Public vs internal:</b> {@link #publicEvent} gates what the reporter/public may see. Internal
 *       responder notes ({@code publicEvent = false}) are never surfaced to the citizen or the public
 *       feed (US-3.4). Read services must filter on this flag — the column is the single source of that
 *       gate.</li>
 *   <li><b>Append-only:</b> like the report itself this extends {@link BaseEntity} for the audit/version
 *       columns, but timeline entries are written once and not edited; corrections append a new event
 *       rather than mutating history (consistent with the platform's immutable-history stance, §25.1).</li>
 *   <li><b>Actor is a UUID:</b> {@link #actorProfileId} is the acting {@code identity.Profile}'s
 *       {@code publicId} (cross-module reference by id), or {@code null} for a system-generated event
 *       (e.g. an auto-close on timeout) or an anonymous reporter's comment on a sensitive case (D-Q1).</li>
 * </ul>
 */
@Entity
@Table(name = "case_event", indexes = {
        @Index(name = "ix_case_event_report", columnList = "report_id"),
        @Index(name = "ix_case_event_type", columnList = "event_type"),
        // Drives the citizen-facing public timeline read: this report's public events, chronological.
        @Index(name = "ix_case_event_report_public", columnList = "report_id, public_event, created_at")
})
@SQLRestriction("deleted = false")
public class CaseEvent extends BaseEntity {

    /** The report this event belongs to (real FK; required). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    /** The kind of timeline entry (status change, comment, …). */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24)
    private CaseEventType eventType;

    /**
     * {@code true} if visible to the reporter/public, {@code false} for an internal responder-only note
     * (US-3.4). Read services <b>must</b> filter on this for citizen/public views.
     */
    @Column(name = "public_event", nullable = false)
    private boolean publicEvent;

    /**
     * {@code publicId} of the acting {@code identity.Profile}, or {@code null} for a system event or an
     * anonymous reporter's action on a sensitive case (cross-module reference by id, D-Q1).
     */
    @Column(name = "actor_profile_id")
    private UUID actorProfileId;

    /**
     * Free-text body (comment text, resolution note copy, or a human description of the change). For a
     * {@link CaseEventType#STATUS_CHANGE} this typically reads "ASSIGNED → IN_PROGRESS"; for a
     * {@link CaseEventType#COMMENT} it is the citizen/responder message.
     */
    @Column(name = "message", length = 4000)
    private String message;

    /** JPA requires a no-arg constructor; application code uses the factory below. */
    protected CaseEvent() {
    }

    /**
     * Appends a timeline event.
     *
     * @param report         the report the event belongs to (required).
     * @param eventType      the kind of event.
     * @param publicEvent    visibility to the reporter/public ({@code false} = internal-only).
     * @param actorProfileId acting profile {@code publicId}, or {@code null} for system/anonymous.
     * @param message        the event body/description.
     */
    public CaseEvent(Report report, CaseEventType eventType, boolean publicEvent, UUID actorProfileId,
                     String message) {
        this.report = report;
        this.eventType = eventType;
        this.publicEvent = publicEvent;
        this.actorProfileId = actorProfileId;
        this.message = message;
    }

    /**
     * Severs the acting-citizen linkage on a data-subject ERASURE (PRD §25.1; ADR-0016 §5.6) — the timeline
     * entry survives as anonymised civic/operational history while its tie to the erased actor is cut.
     *
     * <p>WHY null (not delete): the append-only case timeline is part of the immutable civic record (§25.1);
     * an entry is never mutated away, only its actor reference is de-identified — exactly as a system-generated
     * or anonymous-reporter event ({@code actorProfileId == null}) always reads. <b>Idempotent</b>: an entry
     * whose actor is already {@code null} (system/anonymous, or a prior erasure) is a harmless no-op.</p>
     */
    public void anonymiseActor() {
        this.actorProfileId = null;
    }

    /** @return the owning report. */
    public Report getReport() {
        return report;
    }

    /** @return the event type. */
    public CaseEventType getEventType() {
        return eventType;
    }

    /** @return {@code true} if visible to the reporter/public; {@code false} if internal-only. */
    public boolean isPublicEvent() {
        return publicEvent;
    }

    /** @return the acting profile {@code publicId}, or {@code null} for system/anonymous. */
    public UUID getActorProfileId() {
        return actorProfileId;
    }

    /** @return the event body/description. */
    public String getMessage() {
        return message;
    }
}
