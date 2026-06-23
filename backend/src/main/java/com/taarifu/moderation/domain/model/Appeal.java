package com.taarifu.moderation.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.moderation.domain.model.enums.AppealStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * A challenge to a {@link ModerationAction}, decided by an <b>independent</b> moderator (PRD §25.8,
 * UC-H03; Appendix {@code moderation_appeal_resolved}).
 *
 * <p>Responsibility: the right-of-recourse record. The affected party (the actioned content's author)
 * files an {@link AppealStatus#OPEN} appeal against one action; a <b>different</b> moderator than the one
 * who took that action decides it {@link AppealStatus#UPHELD} or {@link AppealStatus#OVERTURNED} (appeal
 * independence — §25.8, Appendix F footnote ᵉ). At most <b>one live appeal per action</b> is allowed
 * (the {@code action_id} UNIQUE constraint among live rows), so a decision is final unless escalated.</p>
 *
 * <p>The two independence invariants this entity exists to protect (both enforced in the service +
 * regression-tested):</p>
 * <ul>
 *   <li><b>Different-moderator</b> (D16, §25.8 footnote ᵉ): {@link #handledByModeratorId} must not equal
 *       the original action's {@code moderatorProfileId} — a moderator may never adjudicate an appeal of
 *       their <i>own</i> action.</li>
 *   <li><b>Appellant is the affected party</b>: only the actioned content's author may appeal — checked
 *       against the action's frozen {@code subjectAuthorProfileId} (not re-read from the subject).</li>
 * </ul>
 *
 * <p>WHY a real FK to {@link ModerationAction} but a {@code UUID} for the people: the action is in this
 * module (real FK — ARCHITECTURE.md §4.3); appellant and handling moderator are identity-module
 * aggregates referenced by public id only (§3.2).</p>
 */
@Entity
@Table(name = "moderation_appeal",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_mod_appeal_action",
                columnNames = {"action_id"}),
        indexes = {
                @Index(name = "ix_mod_appeal_appellant", columnList = "appellant_profile_id"),
                @Index(name = "ix_mod_appeal_status", columnList = "status"),
                @Index(name = "ix_mod_appeal_handler", columnList = "handled_by_moderator_id")
        })
@SQLRestriction("deleted = false")
public class Appeal extends BaseEntity {

    /** The moderation action being appealed (real FK — same module). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "action_id", nullable = false, updatable = false)
    private ModerationAction action;

    /** Public id of the appellant's profile (the affected content author; cross-module reference). */
    @Column(name = "appellant_profile_id", nullable = false, updatable = false)
    private UUID appellantProfileId;

    /** The appellant's grounds (operator-facing free text); never the moderated content. */
    @Column(name = "grounds", length = 2000, updatable = false)
    private String grounds;

    /** Appeal lifecycle state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AppealStatus status = AppealStatus.OPEN;

    /**
     * Public id of the moderator who decided the appeal, or {@code null} while OPEN. MUST differ from the
     * appealed action's {@code moderatorProfileId} (appeal independence — §25.8); the service enforces and
     * the regression test proves this.
     */
    @Column(name = "handled_by_moderator_id")
    private UUID handledByModeratorId;

    /** The decision rationale (operator-facing); never PII/content. */
    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    /** When the appeal was decided (UTC), or {@code null} while OPEN. */
    @Column(name = "decided_at")
    private Instant decidedAt;

    /** JPA requires a no-arg constructor; application code uses the factory below. */
    protected Appeal() {
    }

    /**
     * Opens an appeal against an action.
     *
     * @param action             the action being appealed.
     * @param appellantProfileId the appellant's profile public id (the affected author).
     * @param grounds            the appellant's grounds (no content/PII).
     * @return a new transient {@link Appeal} in {@link AppealStatus#OPEN}.
     */
    public static Appeal open(ModerationAction action, UUID appellantProfileId, String grounds) {
        Appeal appeal = new Appeal();
        appeal.action = action;
        appeal.appellantProfileId = appellantProfileId;
        appeal.grounds = grounds;
        appeal.status = AppealStatus.OPEN;
        return appeal;
    }

    /**
     * Records the independent decision (OPEN → UPHELD/OVERTURNED).
     *
     * <p>The caller (service) is responsible for having verified {@code moderatorPublicId} is <b>not</b>
     * the original action's moderator before invoking this (appeal independence — §25.8); this method
     * additionally asserts it as a last line of defence so the invariant cannot be bypassed by a
     * mis-wired call site.</p>
     *
     * @param outcome           {@link AppealStatus#UPHELD} or {@link AppealStatus#OVERTURNED}.
     * @param moderatorPublicId the deciding (independent) moderator's public id.
     * @param decisionNote      the decision rationale (no PII/content).
     * @param now               the decision instant.
     * @throws IllegalArgumentException if {@code outcome} is not a terminal decision, or the decider is
     *                                  the same moderator who took the original action.
     */
    public void decide(AppealStatus outcome, UUID moderatorPublicId, String decisionNote, Instant now) {
        if (outcome != AppealStatus.UPHELD && outcome != AppealStatus.OVERTURNED) {
            throw new IllegalArgumentException("Appeal outcome must be UPHELD or OVERTURNED");
        }
        if (moderatorPublicId != null
                && moderatorPublicId.equals(action.getModeratorProfileId())) {
            // Defence-in-depth: the service already blocks this with CONFLICT_OF_INTEREST.
            throw new IllegalArgumentException("Appeal must be handled by a different moderator (§25.8)");
        }
        this.status = outcome;
        this.handledByModeratorId = moderatorPublicId;
        this.decisionNote = decisionNote;
        this.decidedAt = now;
    }

    /** @return the appealed action. */
    public ModerationAction getAction() {
        return action;
    }

    /** @return the appellant's profile public id. */
    public UUID getAppellantProfileId() {
        return appellantProfileId;
    }

    /** @return the appellant's grounds, or {@code null}. */
    public String getGrounds() {
        return grounds;
    }

    /** @return the appeal lifecycle state. */
    public AppealStatus getStatus() {
        return status;
    }

    /** @return the deciding moderator's public id, or {@code null} while OPEN. */
    public UUID getHandledByModeratorId() {
        return handledByModeratorId;
    }

    /** @return the decision rationale, or {@code null}. */
    public String getDecisionNote() {
        return decisionNote;
    }

    /** @return when decided (UTC), or {@code null}. */
    public Instant getDecidedAt() {
        return decidedAt;
    }
}
