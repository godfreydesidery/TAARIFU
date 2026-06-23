package com.taarifu.engagement.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A representative's answer to a {@link Question} (PRD §9.1, §12.2 UC-E10).
 *
 * <p>Responsibility: records the answer text and which representative
 * ({@link #answeredByRepId}, by id only) authored it. One answer per question (a unique constraint on
 * {@code question_id}); publishing an answer flips the {@link Question} to {@code ANSWERED}.</p>
 *
 * <p><b>Integrity (D13/D16):</b> only the <i>targeted</i> representative may answer, and a representative
 * may not answer a question about themselves in a self-serving loop — these conflict-of-interest and
 * target-match guards are applied in the application service via the shared
 * {@link com.taarifu.common.security.ScopeGuard} seam and audited (answer flow is M10; the entity simply
 * holds the record).</p>
 *
 * <p>WHY {@link #answeredByRepId} is a UUID not an FK: the answering representative is owned by the
 * institutions module; referenced by public id to keep the boundary (HARD ISOLATION rule 2).</p>
 */
@Entity
@Table(name = "qa_answer",
        uniqueConstraints = {
                // ONE answer per question (a question is ANSWERED once).
                @UniqueConstraint(name = "uq_qa_answer_question", columnNames = {"question_id"})
        },
        indexes = {
                @Index(name = "ix_qa_answer_answered_by", columnList = "answered_by_rep_id")
        })
@SQLRestriction("deleted = false")
public class Answer extends BaseEntity {

    /** The question answered (real FK — same module). One answer per question. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    /**
     * Public id of the answering representative in the <b>institutions</b> module (by id only). Must match
     * the question's {@code targetRepId} — verified in the service, not the schema.
     */
    @Column(name = "answered_by_rep_id", nullable = false)
    private UUID answeredByRepId;

    /** The answer text. */
    @Column(name = "body", nullable = false, length = 8000)
    private String body;

    /** JPA requires a no-arg constructor; application code uses {@link #of}. */
    protected Answer() {
    }

    /**
     * Builds an answer row (the service flips the question to {@code ANSWERED} in the same transaction).
     *
     * @param question        the question answered (real FK).
     * @param answeredByRepId the answering representative's public id (must equal the question target).
     * @param body            the answer text.
     * @return the populated, transient answer row.
     */
    public static Answer of(Question question, UUID answeredByRepId, String body) {
        Answer a = new Answer();
        a.question = question;
        a.answeredByRepId = answeredByRepId;
        a.body = body;
        return a;
    }

    /** @return the answered question. */
    public Question getQuestion() {
        return question;
    }

    /** @return the answering representative's public id. */
    public UUID getAnsweredByRepId() {
        return answeredByRepId;
    }

    /** @return the answer text. */
    public String getBody() {
        return body;
    }
}
