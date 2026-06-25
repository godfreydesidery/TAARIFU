package com.taarifu.moderation.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.domain.model.enums.ContentSignal;
import com.taarifu.moderation.domain.model.enums.ModerationItemStatus;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * One unit of moderation work — a queue entry for a flagged (or auto-held) subject (PRD §18, US-12.2,
 * UC-H01/H02, §25.8).
 *
 * <p>Responsibility: the prioritised <b>moderator queue</b> row. Many {@link Flag}s on the same subject
 * collapse into <b>one</b> {@code ModerationItem} (the {@code (subject_type, subject_id)} pair is UNIQUE
 * among live items) so moderators review a subject once, not once per flag. It carries the triage
 * {@link ModerationSeverity} (which sets the §25.8 review SLA via {@link #slaDueAt}), the queue
 * {@link ModerationItemStatus}, the claiming moderator, and a {@code flagCount} of distinct flaggers used
 * to escalate severity and order the queue.</p>
 *
 * <p>WHY the subject author is recorded here ({@link #subjectAuthorProfileId}): the conflict-of-interest
 * guard (D16) must block a moderator from actioning their <i>own</i> content; storing the author's id (a
 * cross-module reference, never a FK) lets the action endpoint enforce {@code isNotSelf(author)} without
 * reaching into another module. It is nullable because some subjects (e.g. anonymous sensitive reports)
 * have no surfaced author — in that case the self-action check is vacuously satisfied.</p>
 *
 * <p>GRAIN CONTRACT (load-bearing for D16): this id MUST be in the same grain as the JWT subject —
 * i.e. the author's <b>account public id</b> ({@code app_user.publicId}), because the self-action guard
 * compares it to {@code CurrentUser.publicId()} (the account public id). The wiring that backfills it
 * from the owning module must therefore supply the author's account public id, not a display/profile-only
 * id, or the conflict check would silently never match.</p>
 */
@Entity
@Table(name = "moderation_item",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_mod_item_subject_open",
                columnNames = {"subject_type", "subject_id"}),
        indexes = {
                @Index(name = "ix_mod_item_status_sev", columnList = "status, severity"),
                @Index(name = "ix_mod_item_assignee", columnList = "assigned_moderator_id"),
                @Index(name = "ix_mod_item_sla", columnList = "sla_due_at"),
                @Index(name = "ix_mod_item_author", columnList = "subject_author_profile_id")
        })
@SQLRestriction("deleted = false")
public class ModerationItem extends BaseEntity {

    /** The kind of content under review (copied from the raising flag). */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 24, updatable = false)
    private FlagSubjectType subjectType;

    /** Public id of the content under review (cross-module reference, never a FK). */
    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    /**
     * Public id of the content author's profile (cross-module reference), or {@code null} when the
     * subject has no surfaced author. Backs the D16 self-action conflict check on the action endpoint.
     */
    @Column(name = "subject_author_profile_id", updatable = false)
    private UUID subjectAuthorProfileId;

    /** Triage severity; sets the §25.8 review SLA and queue priority. */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private ModerationSeverity severity;

    /** Queue lifecycle state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ModerationItemStatus status = ModerationItemStatus.PENDING;

    /** Count of distinct flaggers backing this item (used to escalate severity / order the queue). */
    @Column(name = "flag_count", nullable = false)
    private int flagCount;

    /** Public id of the moderator currently reviewing the item, or {@code null} while PENDING. */
    @Column(name = "assigned_moderator_id")
    private UUID assignedModeratorId;

    /**
     * The review-SLA deadline = item creation + {@link ModerationSeverity#reviewTarget()} (§25.8). A
     * worker (M14) surfaces breaches; stored so the deadline is stable even if severity later changes.
     */
    @Column(name = "sla_due_at", nullable = false)
    private Instant slaDueAt;

    /** When a terminal status ({@code ACTIONED}/{@code DISMISSED}) was reached, or {@code null}. */
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Whether the auto-assist scorer raised/held this item (US-12.3, UC-H05). Drives the auto-vs-manual
     * transparency split and {@code moderation_action_taken.was_auto_assisted} when a moderator later
     * actions it. Default {@code false} — a citizen-flagged-only item is manual.
     *
     * <p>WHY a held auto-assisted item never bypasses a human: auto-assist is assist only (D-Q8, R21). This
     * flag records that the screen <i>raised</i> the item for review — it never marks it actioned/removed.</p>
     */
    @Column(name = "auto_assisted", nullable = false)
    private boolean autoAssisted = false;

    /** The top content-safety signal the scorer raised, or {@code null} when not auto-assisted. */
    @Enumerated(EnumType.STRING)
    @Column(name = "auto_signal", length = 16)
    private ContentSignal autoSignal;

    /** The scorer's confidence in {@link #autoSignal} ({@code [0,1]}), or {@code null} when not auto-assisted. */
    @Column(name = "auto_confidence")
    private Double autoConfidence;

    /** JPA requires a no-arg constructor; application code uses the factory below. */
    protected ModerationItem() {
    }

    /**
     * Opens a PENDING queue item for a subject, stamping its SLA deadline from the severity (§25.8).
     *
     * @param subjectType            the kind of content.
     * @param subjectId              the content's public id.
     * @param subjectAuthorProfileId the author's profile public id, or {@code null} if none.
     * @param severity               the initial triage severity.
     * @param now                    the creation instant (from the injected clock — testable).
     * @return a new transient {@link ModerationItem} in {@link ModerationItemStatus#PENDING}.
     */
    public static ModerationItem open(FlagSubjectType subjectType, UUID subjectId,
                                      UUID subjectAuthorProfileId, ModerationSeverity severity,
                                      Instant now) {
        ModerationItem item = new ModerationItem();
        item.subjectType = subjectType;
        item.subjectId = subjectId;
        item.subjectAuthorProfileId = subjectAuthorProfileId;
        item.severity = severity;
        item.status = ModerationItemStatus.PENDING;
        item.flagCount = 0;
        item.slaDueAt = now.plus(severity.reviewTarget());
        return item;
    }

    /**
     * Records one more distinct flagger and, if the new reason is more severe, escalates the severity and
     * tightens the SLA deadline (§25.8 "prioritised by severity").
     *
     * @param escalateTo a severity to raise to if higher than the current one; ignored if not higher.
     * @param now        the current instant for any SLA re-stamp.
     */
    public void recordFlag(ModerationSeverity escalateTo, Instant now) {
        this.flagCount += 1;
        if (escalateTo != null && escalateTo.ordinal() > this.severity.ordinal()) {
            this.severity = escalateTo;
            this.slaDueAt = now.plus(escalateTo.reviewTarget());
        }
    }

    /**
     * Marks this item as <b>raised/held by auto-assist</b> (US-12.3, UC-H05), recording the top signal and
     * confidence and, if the signal implies a higher severity than the current one, escalating it (tightening
     * the SLA — §25.8). This <b>never</b> closes or actions the item — auto-assist is assist only (D-Q8, R21);
     * the item remains for a human moderator to decide.
     *
     * @param signal     the top content-safety signal the scorer raised (never {@code null}).
     * @param confidence the scorer's confidence in the signal ({@code [0,1]}).
     * @param escalateTo a severity to raise to if higher than the current one; {@code null} to leave as-is.
     * @param now        the current instant for any SLA re-stamp.
     */
    public void markAutoAssisted(ContentSignal signal, double confidence, ModerationSeverity escalateTo,
                                 Instant now) {
        this.autoAssisted = true;
        this.autoSignal = signal;
        this.autoConfidence = confidence;
        if (escalateTo != null && escalateTo.ordinal() > this.severity.ordinal()) {
            this.severity = escalateTo;
            this.slaDueAt = now.plus(escalateTo.reviewTarget());
        }
    }

    /**
     * Claims the item for review (PENDING → IN_REVIEW), recording the reviewing moderator.
     *
     * @param moderatorPublicId the claiming moderator's public id.
     */
    public void claim(UUID moderatorPublicId) {
        this.assignedModeratorId = moderatorPublicId;
        this.status = ModerationItemStatus.IN_REVIEW;
    }

    /**
     * Moves the item to a terminal state when an action/dismissal is recorded.
     *
     * @param terminal {@link ModerationItemStatus#ACTIONED} or {@link ModerationItemStatus#DISMISSED}.
     * @param now      the close instant.
     */
    public void close(ModerationItemStatus terminal, Instant now) {
        this.status = terminal;
        this.closedAt = now;
    }

    /** @return whether the item is already in a terminal state. */
    public boolean isTerminal() {
        return status == ModerationItemStatus.ACTIONED || status == ModerationItemStatus.DISMISSED;
    }

    /** @return the kind of content. */
    public FlagSubjectType getSubjectType() {
        return subjectType;
    }

    /** @return the content's public id. */
    public UUID getSubjectId() {
        return subjectId;
    }

    /** @return the author's profile public id, or {@code null}. */
    public UUID getSubjectAuthorProfileId() {
        return subjectAuthorProfileId;
    }

    /** @return the triage severity. */
    public ModerationSeverity getSeverity() {
        return severity;
    }

    /** @return the queue lifecycle state. */
    public ModerationItemStatus getStatus() {
        return status;
    }

    /** @return the count of distinct flaggers. */
    public int getFlagCount() {
        return flagCount;
    }

    /** @return the reviewing moderator's public id, or {@code null}. */
    public UUID getAssignedModeratorId() {
        return assignedModeratorId;
    }

    /** @return the review-SLA deadline (§25.8). */
    public Instant getSlaDueAt() {
        return slaDueAt;
    }

    /** @return the terminal-close instant, or {@code null}. */
    public Instant getClosedAt() {
        return closedAt;
    }

    /** @return whether the auto-assist scorer raised/held this item (US-12.3). */
    public boolean isAutoAssisted() {
        return autoAssisted;
    }

    /** @return the top content-safety signal raised, or {@code null} when not auto-assisted. */
    public ContentSignal getAutoSignal() {
        return autoSignal;
    }

    /** @return the scorer confidence in {@link #getAutoSignal()}, or {@code null} when not auto-assisted. */
    public Double getAutoConfidence() {
        return autoConfidence;
    }
}
