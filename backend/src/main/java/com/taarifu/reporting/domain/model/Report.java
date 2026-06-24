package com.taarifu.reporting.domain.model;

import com.taarifu.common.domain.model.BaseCodedEntity;
import com.taarifu.reporting.domain.model.enums.ReportPriority;
import com.taarifu.reporting.domain.model.enums.ReportStatus;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
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
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.UUID;

/**
 * A citizen-filed issue report and its case state (PRD §10 Epic M3, §12.1, Appendix D; UC-D01).
 *
 * <p>Responsibility: the central case aggregate. It carries the human ticket {@link #getCode() code}
 * ({@code TAR-YYYY-NNNNNN} via {@link BaseCodedEntity} + a DB sequence), the reporter linkage (nullable
 * for anonymous sensitive filings — D-Q1), the {@link #category} FK, the title/description, the incident
 * geo-point + administrative area references, attachment references, visibility, lifecycle
 * {@link #status}, {@link #priority}, the SLA {@link #dueAt}, resolution/confirmation fields, an optional
 * {@link #duplicateOfId} merge target, and the public engagement counters.</p>
 *
 * <h3>Integrity &amp; privacy decisions</h3>
 * <ul>
 *   <li><b>Anonymous sensitive filing (D-Q1):</b> {@link #reporterProfileId} is <b>nullable</b>. For a
 *       sensitive category the citizen may file with no identity linkage; they track the case via the
 *       ticket {@code code} (a pseudonymous handle) rather than via account ownership. The reporter's
 *       PII is therefore simply <i>absent</i>, not encrypted-and-present — the strongest protection
 *       (PRD §25.3).</li>
 *   <li><b>Reporter is a UUID, not a JPA FK:</b> the reporter is an {@code identity.Profile} in another
 *       module; we reference it by {@code publicId} to keep the module boundary clean (no cross-module
 *       schema coupling) and to make anonymity a simple {@code null}. Same rationale for the geography
 *       ward/constituency references — they are {@code geography.Location}/{@code Constituency}
 *       {@code publicId}s resolved through geography's service at file time, then stored as ids
 *       (ARCHITECTURE.md §4.3 cross-module reference-by-id).</li>
 *   <li><b>Routing round-trip is wired (both legs, async):</b> filing emits a {@code REPORT_ROUTED}
 *       outbox event and the responders module creates the OWNER assignment asynchronously (D21,
 *       ADR-0014 §5b), emitting {@code RESPONDER_ASSIGNED} back. The reverse leg is now closed: the
 *       reporting {@code ResponderAssignedHandler} consumes that back-event and (via
 *       {@code ReportService.applySystemAssignment}) sets {@link #assignedResponderId} and transitions the
 *       report {@code NEW -> ASSIGNED} idempotently — it fires only while the report is still {@code NEW},
 *       so an at-least-once relay redelivery never double-transitions (the OWNER assignment is owned by the
 *       responders side; this is the denormalised pointer + auto-transition on the report row).</li>
 *   <li><b>Counters are denormalised, integrity-fenced:</b> {@link #upvotes}/{@link #followers} are
 *       discovery-reach counters only. Per the civic-integrity fence (D18, §23.5) they must <b>never</b>
 *       influence official routing, SLA, priority, or resolution — boosting reach is not buying weight.</li>
 * </ul>
 *
 * <p>Soft-deleted rows are hidden by {@code @SQLRestriction} so default reads never return tombstones.</p>
 */
@Entity
@Table(name = "report", indexes = {
        @Index(name = "ix_report_code", columnList = "code", unique = true),
        @Index(name = "ix_report_reporter", columnList = "reporter_profile_id"),
        @Index(name = "ix_report_category", columnList = "category_id"),
        @Index(name = "ix_report_status", columnList = "status"),
        @Index(name = "ix_report_ward", columnList = "reporter_ward_id"),
        // Drives the public near-me list/map: only PUBLIC, non-terminal-irrelevant rows by ward.
        @Index(name = "ix_report_public_ward", columnList = "visibility, reporter_ward_id, created_at")
})
@SQLRestriction("deleted = false")
public class Report extends BaseCodedEntity {

    /**
     * {@code publicId} of the reporting {@code identity.Profile}, or {@code null} for an anonymous
     * sensitive filing (D-Q1, §25.3). Never a JPA FK (cross-module boundary + anonymity).
     */
    @Column(name = "reporter_profile_id")
    private UUID reporterProfileId;

    /** The issue category this report is filed under (real FK; required). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private IssueCategory category;

    /** Short citizen-supplied title/summary of the issue. */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** Free-text description of the issue (voice-to-text optional on the client). */
    @Column(name = "description", nullable = false, length = 4000)
    private String description;

    /**
     * Optional incident location as a PostGIS point (SRID 4326). Nullable — the citizen may pick an area
     * manually without GPS (UC-D01 step 4). For sensitive reports the point is coarsened/omitted in any
     * shared view (Appendix D.4); this column holds the filed value, redaction is a read-side concern.
     */
    @Column(name = "geo_point", columnDefinition = "geometry(Point,4326)")
    private Point geoPoint;

    /**
     * {@code publicId} of the resolved ward ({@code geography.Location} of type WARD) — minimum pin
     * granularity (PRD §9.0). Stored as id (cross-module reference), required so every report is
     * geo-anchored for routing/queues.
     */
    @Column(name = "reporter_ward_id", nullable = false)
    private UUID reporterWardId;

    /**
     * {@code publicId} of the constituency (Jimbo) in effect for the ward at file time, or {@code null}
     * if the ward has no current electoral mapping. Snapshotted (not re-resolved) so the case's electoral
     * attribution is stable even across re-delimitation (consistent with PRD §25.4 historical attribution).
     */
    @Column(name = "constituency_id")
    private UUID constituencyId;

    /**
     * Comma-separated object-store references to scanned attachments (S3 keys). WHY a delimited string
     * (not a child table) in this increment: attachments are an opaque, append-mostly list the citizen
     * client manages; a join table would be premature (KISS). Each ref points to a virus-scanned object;
     * the scan-on-upload hook is the storage adapter's concern (DEFERRED).
     */
    @Column(name = "attachment_refs", length = 2000)
    private String attachmentRefs;

    /** Public/private discoverability; may be <b>forced</b> PRIVATE by a sensitive category (D-Q1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16)
    private ReportVisibility visibility;

    /** The lifecycle state (PRD §12.1); transitions are guarded by {@link ReportStatus#canTransitionTo}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReportStatus status = ReportStatus.NEW;

    /** Operational priority; may shorten the effective SLA (Appendix D.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private ReportPriority priority = ReportPriority.NORMAL;

    /**
     * {@code publicId} of the assigned OWNER responder office/scope, or {@code null} until routing completes.
     * Set by the reporting {@code ResponderAssignedHandler} (via {@code ReportService.applySystemAssignment})
     * when it consumes the responders' {@code RESPONDER_ASSIGNED} back-event, together with the
     * {@code NEW -> ASSIGNED} transition (D21; ADR-0014 §5b). A bare {@code UUID}, not a JPA FK: the responder
     * is owned by the responders module and referenced across the boundary by public id (ARCHITECTURE §4.3).
     */
    @Column(name = "assigned_responder_id")
    private UUID assignedResponderId;

    /** SLA due instant ({@code filedAt + category TTR}); drives breach detection (DEFERRED engine). */
    @Column(name = "due_at")
    private Instant dueAt;

    /** Resolution note set when a responder marks the case RESOLVED (required by US-3.4). */
    @Column(name = "resolution", length = 4000)
    private String resolution;

    /**
     * Citizen confirmation outcome on a RESOLVED case: {@code null} = pending, {@code true} = confirmed
     * (→ CLOSED), {@code false} = disputed (→ REOPENED) (US-3.5, UC-D11/12/13).
     */
    @Column(name = "confirmation")
    private Boolean confirmation;

    /**
     * If this report was merged as a duplicate, the {@code publicId} of the canonical report it points to
     * (US-3.8). Set together with a transition to {@link ReportStatus#DUPLICATE}.
     */
    @Column(name = "duplicate_of_id")
    private UUID duplicateOfId;

    /** Discovery-reach upvote counter (US-3.7). Integrity-fenced: never affects routing/SLA/priority. */
    @Column(name = "upvotes", nullable = false)
    private long upvotes = 0;

    /** Discovery-reach follower counter (US-3.7). Integrity-fenced: never affects routing/SLA/priority. */
    @Column(name = "followers", nullable = false)
    private long followers = 0;

    /** JPA requires a no-arg constructor; application code uses the factory below. */
    protected Report() {
    }

    /**
     * Files a new report in the {@code NEW} state. The ticket {@code code} is assigned separately by the
     * service via {@code CodeGenerator} before persist (per {@link BaseCodedEntity}).
     *
     * @param reporterProfileId reporter profile {@code publicId}, or {@code null} for anonymous (D-Q1).
     * @param category          the (required) issue category.
     * @param title             citizen title.
     * @param description       citizen description.
     * @param geoPoint          optional incident point, or {@code null}.
     * @param reporterWardId    resolved ward {@code publicId} (required, minimum pin granularity).
     * @param constituencyId    constituency {@code publicId} in effect, or {@code null}.
     * @param attachmentRefs    delimited attachment object-store refs, or {@code null}.
     * @param visibility        the effective visibility (already forced PRIVATE by the service if sensitive).
     * @param priority          the filed priority.
     * @param dueAt             SLA due instant ({@code filedAt + TTR}).
     */
    public Report(UUID reporterProfileId, IssueCategory category, String title, String description,
                  Point geoPoint, UUID reporterWardId, UUID constituencyId, String attachmentRefs,
                  ReportVisibility visibility, ReportPriority priority, Instant dueAt) {
        this.reporterProfileId = reporterProfileId;
        this.category = category;
        this.title = title;
        this.description = description;
        this.geoPoint = geoPoint;
        this.reporterWardId = reporterWardId;
        this.constituencyId = constituencyId;
        this.attachmentRefs = attachmentRefs;
        this.visibility = visibility;
        this.priority = priority;
        this.status = ReportStatus.NEW;
        this.dueAt = dueAt;
    }

    /**
     * Applies a guarded status transition. The legality check is the caller's responsibility
     * (the service consults {@link ReportStatus#canTransitionTo} and records a {@code CaseEvent}); this
     * method is the single mutation point so the field is never set without going through the state field.
     *
     * @param target the new (already-validated) status.
     */
    public void setStatus(ReportStatus target) {
        this.status = target;
    }

    /**
     * Marks the case resolved with a note (US-3.4): sets the resolution text and the {@code RESOLVED}
     * status. Confirmation is reset to pending so the citizen confirm/dispute window opens fresh.
     *
     * @param resolutionNote the required resolution note.
     */
    public void resolve(String resolutionNote) {
        this.resolution = resolutionNote;
        this.confirmation = null;
        this.status = ReportStatus.RESOLVED;
    }

    /**
     * Records the responder this report is assigned to (D21). WHY only the id (not a FK): the responder is
     * owned by the responders module, referenced by {@code publicId} across the boundary (ARCHITECTURE
     * §4.3). The status transition to {@code ASSIGNED} is applied separately via {@link #setStatus} through
     * the service's guarded {@code transition}, so this method only carries the assignment reference.
     *
     * @param responderPublicId the assigned responder's public id.
     */
    public void assignResponder(UUID responderPublicId) {
        this.assignedResponderId = responderPublicId;
    }

    /**
     * Records the citizen's confirm/dispute decision on a RESOLVED case and moves to the resulting state
     * (US-3.5): confirm → {@code CLOSED}, dispute → {@code REOPENED}.
     *
     * @param confirmed {@code true} to confirm (close), {@code false} to dispute (reopen).
     */
    public void applyConfirmation(boolean confirmed) {
        this.confirmation = confirmed;
        this.status = confirmed ? ReportStatus.CLOSED : ReportStatus.REOPENED;
    }

    /**
     * Marks this report a duplicate of a canonical report (US-3.8).
     *
     * @param canonicalReportId the canonical report's {@code publicId}.
     */
    public void markDuplicateOf(UUID canonicalReportId) {
        this.duplicateOfId = canonicalReportId;
        this.status = ReportStatus.DUPLICATE;
    }

    /** Increments the discovery-reach upvote counter (integrity-fenced; never affects routing/SLA). */
    public void incrementUpvotes() {
        this.upvotes++;
    }

    /** Increments the discovery-reach follower counter (integrity-fenced; never affects routing/SLA). */
    public void incrementFollowers() {
        this.followers++;
    }

    /** @return the reporter profile {@code publicId}, or {@code null} for an anonymous filing. */
    public UUID getReporterProfileId() {
        return reporterProfileId;
    }

    /** @return {@code true} if this report has no reporter linkage (anonymous sensitive filing, D-Q1). */
    public boolean isAnonymous() {
        return reporterProfileId == null;
    }

    /** @return the issue category. */
    public IssueCategory getCategory() {
        return category;
    }

    /** @return the citizen title. */
    public String getTitle() {
        return title;
    }

    /** @return the citizen description. */
    public String getDescription() {
        return description;
    }

    /** @return the optional incident point, or {@code null}. */
    public Point getGeoPoint() {
        return geoPoint;
    }

    /** @return the resolved ward {@code publicId}. */
    public UUID getReporterWardId() {
        return reporterWardId;
    }

    /** @return the constituency {@code publicId} in effect, or {@code null}. */
    public UUID getConstituencyId() {
        return constituencyId;
    }

    /** @return the delimited attachment refs, or {@code null}. */
    public String getAttachmentRefs() {
        return attachmentRefs;
    }

    /** @return the effective visibility. */
    public ReportVisibility getVisibility() {
        return visibility;
    }

    /** @return the lifecycle status. */
    public ReportStatus getStatus() {
        return status;
    }

    /** @return the operational priority. */
    public ReportPriority getPriority() {
        return priority;
    }

    /** @return the assigned OWNER responder {@code publicId}, or {@code null} until routing assigns one (D21). */
    public UUID getAssignedResponderId() {
        return assignedResponderId;
    }

    /** @return the SLA due instant, or {@code null}. */
    public Instant getDueAt() {
        return dueAt;
    }

    /** @return the resolution note, or {@code null} if not yet resolved. */
    public String getResolution() {
        return resolution;
    }

    /** @return the citizen confirmation outcome: {@code null} pending, {@code true}/{@code false}. */
    public Boolean getConfirmation() {
        return confirmation;
    }

    /** @return the canonical report {@code publicId} if this is a duplicate, else {@code null}. */
    public UUID getDuplicateOfId() {
        return duplicateOfId;
    }

    /** @return the discovery-reach upvote count. */
    public long getUpvotes() {
        return upvotes;
    }

    /** @return the discovery-reach follower count. */
    public long getFollowers() {
        return followers;
    }
}
