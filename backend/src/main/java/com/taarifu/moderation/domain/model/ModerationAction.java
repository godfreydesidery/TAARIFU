package com.taarifu.moderation.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.moderation.domain.model.enums.ModerationActionType;
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
 * An <b>append-only</b> record of a decision a moderator took on a queue item (PRD §18, US-12.2,
 * UC-H02; Appendix {@code moderation_action_taken}).
 *
 * <p>Responsibility: the immutable moderation audit trail. Each row is the moderator, the
 * {@link ModerationActionType}, a machine {@code reasonCode}, and {@code takenAt}. Rows are <b>never
 * updated or deleted</b> — a reversal (e.g. an appeal overturning a takedown) is a <i>new</i>
 * {@link ModerationActionType#APPROVE} action, never a mutation of the original (§18, §25.8). The
 * append-only invariant is enforced at the database in the V41 migration (UPDATE/DELETE revoked from the
 * runtime role), the same pattern as {@code audit_event} (ARCHITECTURE.md §5.2 / V5).</p>
 *
 * <p>WHY a real FK to {@link ModerationItem} (not a UUID reference): the queue item is in <b>this</b>
 * module, so a proper foreign key is required (ARCHITECTURE.md §4.3 — real FKs within a module). The
 * acting moderator is a {@code UUID} ({@link #moderatorProfileId}) because identity is a separate module
 * referenced by public id only.</p>
 *
 * <p>WHY {@code subjectAuthorProfileId} is copied onto the action: it freezes "whose content was actioned"
 * at action time so an {@link Appeal} can verify the appellant is the affected party and the appeal
 * decision can verify it is handled by a moderator <i>other than</i> {@link #moderatorProfileId} (D16,
 * §25.8 appeal independence) — both without re-reading the (possibly since-changed) item.</p>
 */
@Entity
@Table(name = "moderation_action", indexes = {
        @Index(name = "ix_mod_action_item", columnList = "item_id"),
        @Index(name = "ix_mod_action_moderator", columnList = "moderator_profile_id"),
        @Index(name = "ix_mod_action_type_time", columnList = "type, taken_at")
})
@SQLRestriction("deleted = false")
public class ModerationAction extends BaseEntity {

    /** The queue item this action resolves (real FK — same module). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false, updatable = false)
    private ModerationItem item;

    /** The decision taken. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16, updatable = false)
    private ModerationActionType type;

    /** Public id of the acting moderator's profile (cross-module reference to identity, never a FK). */
    @Column(name = "moderator_profile_id", nullable = false, updatable = false)
    private UUID moderatorProfileId;

    /** Public id of the actioned content's author, or {@code null} when none — frozen at action time. */
    @Column(name = "subject_author_profile_id", updatable = false)
    private UUID subjectAuthorProfileId;

    /** Machine reason for the decision (e.g. {@code RULE_HARASSMENT}); never PII/content. */
    @Column(name = "reason_code", nullable = false, length = 64, updatable = false)
    private String reasonCode;

    /** Optional moderator note (operator-facing); never the moderated content itself. */
    @Column(name = "note", length = 1000, updatable = false)
    private String note;

    /** When the action was taken (UTC) — append-only timestamp. */
    @Column(name = "taken_at", nullable = false, updatable = false)
    private Instant takenAt;

    /** JPA requires a no-arg constructor; application code uses the factory below. */
    protected ModerationAction() {
    }

    /**
     * Records a moderation action (append-only).
     *
     * @param item                   the queue item being resolved.
     * @param type                   the decision taken.
     * @param moderatorProfileId     the acting moderator's profile public id.
     * @param subjectAuthorProfileId the actioned content's author profile public id, or {@code null}.
     * @param reasonCode             machine reason (never PII/content).
     * @param note                   optional operator note (never content).
     * @param now                    the action instant.
     * @return a new transient {@link ModerationAction}.
     */
    public static ModerationAction record(ModerationItem item, ModerationActionType type,
                                          UUID moderatorProfileId, UUID subjectAuthorProfileId,
                                          String reasonCode, String note, Instant now) {
        ModerationAction a = new ModerationAction();
        a.item = item;
        a.type = type;
        a.moderatorProfileId = moderatorProfileId;
        a.subjectAuthorProfileId = subjectAuthorProfileId;
        a.reasonCode = reasonCode;
        a.note = note;
        a.takenAt = now;
        return a;
    }

    /** @return the resolved queue item. */
    public ModerationItem getItem() {
        return item;
    }

    /** @return the decision taken. */
    public ModerationActionType getType() {
        return type;
    }

    /** @return the acting moderator's profile public id. */
    public UUID getModeratorProfileId() {
        return moderatorProfileId;
    }

    /** @return the actioned content author's profile public id, or {@code null}. */
    public UUID getSubjectAuthorProfileId() {
        return subjectAuthorProfileId;
    }

    /** @return the machine reason code. */
    public String getReasonCode() {
        return reasonCode;
    }

    /** @return the optional operator note, or {@code null}. */
    public String getNote() {
        return note;
    }

    /** @return when the action was taken (UTC). */
    public Instant getTakenAt() {
        return takenAt;
    }
}
