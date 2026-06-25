package com.taarifu.engagement.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * One respondent's answers to a {@link Survey} (PRD §9.1, §12.2 UC-E07).
 *
 * <p>Responsibility: records that a respondent (an identity {@code Profile}, referenced by
 * {@link #responderProfileId} — by id only) answered a survey, with their {@link #answers} payload. The
 * defining invariant is <b>one response per person per survey</b> (US-8.2 "one response"), enforced in
 * the database by a composite unique constraint on {@code (survey_id, responder_profile_id)} scoped to
 * live rows.</p>
 *
 * <p><b>Integrity (D18, PRD §23.5):</b> for a <i>binding</i> poll the response is a democratic-weight
 * act — T3 + one-per-person, never balance-gated. The T3 gate is the {@code @RequiresTier("T3")} on the
 * respond endpoint when the survey is binding; the one-per-person guarantee is <b>this unique
 * constraint</b>. For a non-binding survey the gate is T2 (PRD §7.3). Token balance never appears in the
 * authorisation path.</p>
 *
 * <p>WHY the responder id is retained even for an {@link Survey#isAnonymous() anonymous} survey: the
 * one-per-person guarantee needs a stable key, so the id is stored but is <b>never exposed</b> in results
 * for anonymous surveys (PDPA data-minimisation, PRD §18). This is the deliberate trade-off between
 * ballot-stuffing prevention and anonymity, resolved in favour of integrity with non-exposure.</p>
 */
@Entity
@Table(name = "survey_response",
        uniqueConstraints = {
                // ONE response per (survey, responder) — the one-response-per-person invariant (US-8.2).
                @UniqueConstraint(name = "uq_survey_response_once",
                        columnNames = {"survey_id", "responder_profile_id"})
        },
        indexes = {
                @Index(name = "ix_survey_response_survey", columnList = "survey_id"),
                @Index(name = "ix_survey_response_responder", columnList = "responder_profile_id")
        })
@SQLRestriction("deleted = false")
public class SurveyResponse extends BaseEntity {

    /** The survey answered (real FK — same module). Many responses per survey. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "survey_id", nullable = false)
    private Survey survey;

    /**
     * Public id of the responding identity {@code Profile} (by id only). With {@link #survey} this forms
     * the one-per-person unique key. Kept even when the survey is anonymous (never exposed then).
     */
    @Column(name = "responder_profile_id", nullable = false)
    private UUID responderProfileId;

    /**
     * The answers payload as a JSON <i>string</i>: an array aligning to the survey's questions (e.g.
     * {@code [{questionIndex, selected[], text, scale}]}). Bounded {@code varchar} text blob in this
     * Phase-2 scaffold (mirrors {@link Survey#getQuestions()} — NOT native {@code jsonb}, see that field's
     * note); validated/parsed at the edge.
     */
    @Column(name = "answers", length = 16000)
    private String answers;

    /** JPA requires a no-arg constructor; application code uses {@link #of}. */
    protected SurveyResponse() {
    }

    /**
     * Builds a response row. The one-per-person guarantee is the DB unique constraint's job.
     *
     * @param survey             the survey answered (real FK).
     * @param responderProfileId the responder's identity {@code Profile} public id (by id only).
     * @param answers            the JSON answers payload.
     * @return the populated, transient response row.
     */
    public static SurveyResponse of(Survey survey, UUID responderProfileId, String answers) {
        SurveyResponse r = new SurveyResponse();
        r.survey = survey;
        r.responderProfileId = responderProfileId;
        r.answers = answers;
        return r;
    }

    /** @return the answered survey. */
    public Survey getSurvey() {
        return survey;
    }

    /**
     * De-identifies this survey response on a data-subject ERASURE (PRD §25.1; ADR-0016 §5.6) — the response
     * <b>survives as an anonymised data point</b> in the survey's aggregate while its tie to the now-erased
     * responder is severed.
     *
     * <p>WHY a tombstone token rather than {@code null}: {@link #responderProfileId} is {@code NOT NULL} and is
     * half of the {@code (survey, responder)} one-per-person unique key (UC-E07). The caller supplies a
     * <b>deterministic</b> per-subject token so the response still counts once, stays unique on its survey, and
     * the responder is no longer recoverable; a redelivery is a no-op (matches nothing on the original id —
     * ADR-0014 §3). The {@link #answers} payload is preserved as anonymised aggregate survey data.</p>
     *
     * @param responderTombstone the deterministic, non-account per-subject token replacing the real responder.
     */
    public void anonymiseResponder(UUID responderTombstone) {
        this.responderProfileId = responderTombstone;
    }

    /** @return the responder's profile public id. */
    public UUID getResponderProfileId() {
        return responderProfileId;
    }

    /** @return the JSON answers payload. */
    public String getAnswers() {
        return answers;
    }
}
