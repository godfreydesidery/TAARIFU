package com.taarifu.moderation.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.moderation.domain.model.enums.FlagReason;
import com.taarifu.moderation.domain.model.enums.FlagStatus;
import com.taarifu.moderation.domain.model.enums.FlagSubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A single citizen's report that a piece of content violates the rules (PRD §18, US-12.1, UC-E13/H01).
 *
 * <p>Responsibility: captures <i>who</i> flagged <i>what</i> and <i>why</i>. The flagged content is
 * referenced <b>only</b> by {@code (subjectType, subjectId)} — this module never imports the owning
 * module (reporting/engagement/communications/identity), keeping the boundary clean (ARCHITECTURE.md
 * §3.2). Many flags on the same subject are collapsed into one {@link ModerationItem} for review; this
 * row tracks the individual flagger's submission so they can be given feedback (US-12.1).</p>
 *
 * <p>WHY {@code flaggerProfileId} is a {@code UUID}, not a FK to a {@code Profile}: identity is a separate
 * module and we reference its aggregates by public id, never by a cross-module FK (ARCHITECTURE.md §3.2,
 * §4.3 cross-module convention). The pair {@code (flaggerProfileId, subjectType, subjectId)} is UNIQUE so
 * one citizen cannot inflate a subject's flag count by flagging it repeatedly (anti-brigading; the count
 * that drives severity must reflect <i>distinct</i> flaggers).</p>
 *
 * <p>WHY no token balance anywhere on the flag path: flagging is a civic-core safety action available to
 * any authenticated citizen (T1+) — it must never be priced or gated by tokens (integrity fence, D18,
 * §23.5); this entity therefore carries nothing token-related by construction.</p>
 */
@Entity
@Table(name = "moderation_flag",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_mod_flag_flagger_subject",
                columnNames = {"flagger_profile_id", "subject_type", "subject_id"}),
        indexes = {
                @Index(name = "ix_mod_flag_subject", columnList = "subject_type, subject_id"),
                @Index(name = "ix_mod_flag_flagger", columnList = "flagger_profile_id"),
                @Index(name = "ix_mod_flag_status", columnList = "status")
        })
@SQLRestriction("deleted = false")
public class Flag extends BaseEntity {

    /** The kind of content flagged (REPORT/COMMENT/…). */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 24, updatable = false)
    private FlagSubjectType subjectType;

    /**
     * Public id of the flagged content in its owning module — a cross-module reference, never a FK
     * (ARCHITECTURE.md §3.2). Resolving it to a concrete record is a {@code // TODO(wiring)}.
     */
    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    /** Public id of the flagging citizen's profile (cross-module reference to identity, not a FK). */
    @Column(name = "flagger_profile_id", nullable = false, updatable = false)
    private UUID flaggerProfileId;

    /** Why it was flagged (drives the default queue severity). */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 16, updatable = false)
    private FlagReason reason;

    /** Optional free-text detail (required-by-UI when {@code reason = OTHER}); never holds the content. */
    @Column(name = "detail", length = 1000)
    private String detail;

    /** Flag lifecycle state (OPEN until the raised queue item is actioned/dismissed). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FlagStatus status = FlagStatus.OPEN;

    /**
     * The queue item this flag was attached to (the public id of a {@link ModerationItem}); {@code null}
     * only transiently between flag insert and item attach within the same transaction.
     */
    @Column(name = "moderation_item_id")
    private UUID moderationItemId;

    /** JPA requires a no-arg constructor; application code uses the factory below. */
    protected Flag() {
    }

    /**
     * Creates an OPEN flag.
     *
     * @param subjectType      the kind of content flagged.
     * @param subjectId        the flagged content's public id.
     * @param flaggerProfileId the flagging citizen's profile public id.
     * @param reason           why it was flagged.
     * @param detail           optional free-text detail (no content/PII).
     * @return a new transient {@link Flag} in {@link FlagStatus#OPEN}.
     */
    public static Flag open(FlagSubjectType subjectType, UUID subjectId, UUID flaggerProfileId,
                            FlagReason reason, String detail) {
        Flag f = new Flag();
        f.subjectType = subjectType;
        f.subjectId = subjectId;
        f.flaggerProfileId = flaggerProfileId;
        f.reason = reason;
        f.detail = detail;
        f.status = FlagStatus.OPEN;
        return f;
    }

    /**
     * Attaches this flag to the queue item it raised/joined.
     *
     * @param moderationItemPublicId the {@link ModerationItem} public id.
     */
    public void attachTo(UUID moderationItemPublicId) {
        this.moderationItemId = moderationItemPublicId;
    }

    /**
     * Marks the flag as resolved/dismissed once its queue item reaches a terminal state.
     *
     * @param terminal {@link FlagStatus#RESOLVED} or {@link FlagStatus#DISMISSED}.
     */
    public void close(FlagStatus terminal) {
        this.status = terminal;
    }

    /** @return the kind of content flagged. */
    public FlagSubjectType getSubjectType() {
        return subjectType;
    }

    /** @return the flagged content's public id (cross-module reference). */
    public UUID getSubjectId() {
        return subjectId;
    }

    /** @return the flagging citizen's profile public id. */
    public UUID getFlaggerProfileId() {
        return flaggerProfileId;
    }

    /** @return why it was flagged. */
    public FlagReason getReason() {
        return reason;
    }

    /** @return the optional free-text detail, or {@code null}. */
    public String getDetail() {
        return detail;
    }

    /** @return the flag lifecycle state. */
    public FlagStatus getStatus() {
        return status;
    }

    /** @return the attached queue item's public id, or {@code null}. */
    public UUID getModerationItemId() {
        return moderationItemId;
    }
}
