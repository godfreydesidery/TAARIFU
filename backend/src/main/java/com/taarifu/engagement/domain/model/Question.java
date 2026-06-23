package com.taarifu.engagement.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.engagement.domain.model.enums.QuestionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A public Q&A question put to a representative (PRD §9.1 Question, §12.2 M10).
 *
 * <p>Responsibility: the question aggregate — the asker ({@link #askerProfileId}, by id only), the
 * targeted representative ({@link #targetRepId}, an institutions-module public id, by id only), the
 * {@link #body}, an {@link #upvotes} counter, and its lifecycle {@link #status}. The
 * {@link Answer} (when given) is a separate aggregate.</p>
 *
 * <p><b>Tier gate:</b> asking a question is a <b>T2</b> action (PRD §7.3 "ask Q&A"); the gate is the
 * {@code @RequiresTier("T2")} on the ask endpoint. Asking is <i>not</i> a binding democratic-weight act,
 * so it is not T3 and not one-per-person — a citizen may ask multiple questions (subject to free-quota/
 * rate limits handled by the tokens module, referenced later).</p>
 *
 * <p><b>Integrity (D13/D16):</b> a representative may not ask a question targeting <i>themselves</i>, and
 * (in M10's answer flow) may not upvote their own question; those conflict-of-interest guards are applied
 * in the service via the shared {@link com.taarifu.common.security.ScopeGuard} seam and audited, not in
 * this entity.</p>
 *
 * <p>WHY both ids are UUIDs not FKs: the asker is an identity {@code Profile} and the target a
 * Representative (institutions) — both in other modules; engagement references them by public id to keep
 * the boundary (ARCHITECTURE §3.2; HARD ISOLATION rule 2 — no institutions import).</p>
 */
@Entity
@Table(name = "qa_question", indexes = {
        @Index(name = "ix_qa_question_status", columnList = "status"),
        @Index(name = "ix_qa_question_target_rep", columnList = "target_rep_id"),
        @Index(name = "ix_qa_question_asker", columnList = "asker_profile_id")
})
@SQLRestriction("deleted = false")
public class Question extends BaseEntity {

    /** Public id of the asking identity {@code Profile} (by id only). */
    @Column(name = "asker_profile_id", nullable = false)
    private UUID askerProfileId;

    /**
     * Public id of the targeted representative in the <b>institutions</b> module (by id only — no import).
     * Resolving/validating the target is a later integration step (// TODO(wiring) in the service).
     */
    @Column(name = "target_rep_id", nullable = false)
    private UUID targetRepId;

    /** The question text. */
    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    /**
     * Count of upvotes (US-E11). Denormalised counter on the row; the per-voter one-vote guarantee
     * (and the no-self-upvote conflict guard) belong to the upvote sub-feature in M10 and are referenced,
     * not built, in this scaffold.
     */
    @Column(name = "upvotes", nullable = false)
    private int upvotes = 0;

    /** Current lifecycle state (PRD §12.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private QuestionStatus status = QuestionStatus.OPEN;

    /** JPA requires a no-arg constructor; application code uses {@link #ask}. */
    protected Question() {
    }

    /**
     * Creates a new question in {@code OPEN}.
     *
     * @param askerProfileId the asker's identity {@code Profile} public id (by id only).
     * @param targetRepId    the targeted representative's public id (institutions module; by id only).
     * @param body           the question text.
     * @return the populated, transient question (status {@code OPEN}).
     */
    public static Question ask(UUID askerProfileId, UUID targetRepId, String body) {
        Question q = new Question();
        q.askerProfileId = askerProfileId;
        q.targetRepId = targetRepId;
        q.body = body;
        q.status = QuestionStatus.OPEN;
        q.upvotes = 0;
        return q;
    }

    /** Marks the question {@code ANSWERED} (called when an {@link Answer} is published, UC-E10). */
    public void markAnswered() {
        this.status = QuestionStatus.ANSWERED;
    }

    /** @return whether the question is publicly listed (OPEN or ANSWERED). */
    public boolean isPubliclyVisible() {
        return this.status == QuestionStatus.OPEN || this.status == QuestionStatus.ANSWERED;
    }

    /** @return the asker's profile public id. */
    public UUID getAskerProfileId() {
        return askerProfileId;
    }

    /** @return the targeted representative's public id. */
    public UUID getTargetRepId() {
        return targetRepId;
    }

    /** @return the question text. */
    public String getBody() {
        return body;
    }

    /** @return the upvote count. */
    public int getUpvotes() {
        return upvotes;
    }

    /** @return the current lifecycle state. */
    public QuestionStatus getStatus() {
        return status;
    }
}
