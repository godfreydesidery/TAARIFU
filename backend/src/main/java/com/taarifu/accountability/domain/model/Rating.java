package com.taarifu.accountability.domain.model;

import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A single citizen's binding rating of a representative/office/project for one period (PRD §10 Epic M6,
 * US-6.2; §23 civic-integrity fence; D13/D16/D18).
 *
 * <p>Responsibility: the unit of accountability scoring. Rating a representative is a <b>binding civic
 * action</b>, so its integrity is fenced server-side:</p>
 * <ul>
 *   <li><b>Tier (D13):</b> only T3 citizens may rate a representative — enforced by {@code @RequiresTier
 *       ("T3")} on the create endpoint (live-resolved, never the token claim).</li>
 *   <li><b>One per person (D16):</b> the DB unique constraint
 *       {@code (subject_type, subject_id, rater_profile_id, period)} makes a second rating for the same
 *       period a hard {@code 409} — one person, one rating, regardless of token balance.</li>
 *   <li><b>No self-action (D16):</b> a representative cannot rate themselves — checked via
 *       {@code ScopeGuard.isNotSelf} before persist (conflict-of-interest).</li>
 *   <li><b>Electoral scope (D13, two-tier):</b> a citizen may only rate a representative they are an
 *       elector of — enforced in {@code RatingService} via institutions'
 *       {@code RepresentativeQueryApi.constituencyOf}/{@code wardOf} × identity's
 *       {@code ElectoralScopeApi}. The same ports throw {@code NOT_FOUND} for a phantom representative, so
 *       a citizen can never rate a non-existent rep.</li>
 *   <li><b>Fence (D18, §23):</b> this entity and its service path carry <b>no token balance and no token
 *       collaborator</b>. A token balance must NEVER appear in the authorization path of a rating — wealth
 *       cannot buy democratic weight. The aggregate score is computed from these append-only rows only.</li>
 * </ul>
 *
 * <p>Reps cannot edit or delete others' ratings (US-6.2): ratings are owned by the rater; only the rater
 * may update their own row, and the no-self-action rule prevents a rep from being the rater of their own
 * subject. The aggregate is always recomputed, never stored on the subject.</p>
 *
 * <p>WHY {@code subjectId} and {@code raterProfileId} are opaque {@link UUID}s, not FKs: the subject lives
 * in another module (institutions/projects) and the rater's profile in <b>identity</b>; this module is
 * isolated and references them by public id. For a {@code REPRESENTATIVE} subject, existence and electoral
 * scope are validated in {@code RatingService} via institutions' published {@code RepresentativeQueryApi}.
 * ({@code OFFICE}/{@code PROJECT} subjects have no owning module/port yet, so their existence is not yet
 * validated — // TODO(wiring) once those seams exist.)</p>
 */
@Entity
@Table(name = "rating",
        uniqueConstraints = @UniqueConstraint(
                // ONE rating per (rater, subject, period) — the one-per-person integrity invariant (D16).
                name = "ux_rating_one_per_rater_subject_period",
                columnNames = {"subject_type", "subject_id", "rater_profile_id", "period"}),
        indexes = {
                @Index(name = "ix_rating_subject", columnList = "subject_type, subject_id"),
                @Index(name = "ix_rating_rater", columnList = "rater_profile_id"),
                @Index(name = "ix_rating_period", columnList = "period")
        })
// Score domain guard 1..5 — declared on the entity so Hibernate generates the same CHECK the V46
// migration declares (ck_rating_score), keeping entity↔DDL parity under both create-drop and validate.
@Check(name = "ck_rating_score", constraints = "score BETWEEN 1 AND 5")
@SQLRestriction("deleted = false")
public class Rating extends BaseEntity {

    /** What kind of subject this rating is about (REPRESENTATIVE/OFFICE/PROJECT). */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private RatingSubjectType subjectType;

    /**
     * Public id of the rated subject (institutions/projects module — referenced by id only, never an FK).
     * For a {@code REPRESENTATIVE} subject, existence + electoral scope are validated in
     * {@code RatingService} via institutions' {@code RepresentativeQueryApi} (× identity's
     * {@code ElectoralScopeApi}). {@code OFFICE}/{@code PROJECT} subjects have no owning module/port yet.
     */
    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    /**
     * Public id of the rater's profile (identity module). Part of the one-per-person uniqueness key; the
     * rater is the owner of the row (only they may update it).
     */
    @Column(name = "rater_profile_id", nullable = false)
    private UUID raterProfileId;

    /** The score, constrained 1..5 (DB CHECK + Bean Validation at the edge). */
    @Column(name = "score", nullable = false)
    private int score;

    /** Optional free-text comment (moderated downstream — US-6.2). */
    @Column(name = "comment", length = 2000)
    private String comment;

    /** The rating period, e.g. {@code "2026-Q2"} — the third axis of one-per-person uniqueness. */
    @Column(name = "period", nullable = false, length = 16)
    private String period;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Rating() {
    }

    /**
     * Creates a binding rating. The caller MUST have already passed the fence (tier + no-self + scope) —
     * this factory is a pure constructor and performs no authorization (the service owns the fence).
     *
     * @param subjectType    the subject kind.
     * @param subjectId      public id of the rated subject.
     * @param raterProfileId public id of the rater's profile (identity).
     * @param score          1..5 (validated upstream and by the DB CHECK).
     * @param comment        optional comment, or {@code null}.
     * @param period         the rating period (e.g. {@code "2026-Q2"}).
     * @return the populated, transient entity.
     */
    public static Rating create(RatingSubjectType subjectType, UUID subjectId, UUID raterProfileId,
                                int score, String comment, String period) {
        Rating r = new Rating();
        r.subjectType = subjectType;
        r.subjectId = subjectId;
        r.raterProfileId = raterProfileId;
        r.score = score;
        r.comment = comment;
        r.period = period;
        return r;
    }

    /**
     * Updates the rater's own rating (score/comment) within the same period. Only the owning rater may
     * call this (enforced in the service); reps can never edit others' ratings (US-6.2).
     *
     * @param score   the new score (1..5).
     * @param comment the new comment, or {@code null} to clear.
     */
    public void revise(int score, String comment) {
        this.score = score;
        this.comment = comment;
    }

    /**
     * De-identifies this rating on a data-subject ERASURE (PRD §18, §25.1, §23.5; ADR-0016 §5.6) — the rating
     * <b>survives as a counted accountability score</b> while its tie to the now-erased rater is severed and
     * any free-text comment (citizen PII) is cleared.
     *
     * <p>WHY a tombstone token rather than {@code null}: {@link #raterProfileId} is {@code NOT NULL} and is
     * part of the {@code (subject, rater, period)} one-per-person unique key (D16). Nulling it is impossible;
     * deleting the row would silently rewrite the representative's aggregate score (the integrity fence in
     * arithmetic form — wealth/erasure cannot move a democratic tally, §23.5). So the rater is replaced by a
     * caller-supplied <b>deterministic</b> per-subject token — the rating still counts toward the computed
     * aggregate, the row stays unique for its subject+period, and the rater is no longer recoverable.
     * Determinism makes a redelivery a no-op (the second pass matches nothing on the original rater id —
     * at-least-once safe, ADR-0014 §3).</p>
     *
     * @param raterTombstone the deterministic, non-account per-subject token replacing the real rater id.
     */
    public void anonymiseRater(UUID raterTombstone) {
        this.raterProfileId = raterTombstone;
        this.comment = null;
    }

    /** @return the subject kind. */
    public RatingSubjectType getSubjectType() {
        return subjectType;
    }

    /** @return the rated subject's public id. */
    public UUID getSubjectId() {
        return subjectId;
    }

    /** @return the rater's profile public id (owner of this row). */
    public UUID getRaterProfileId() {
        return raterProfileId;
    }

    /** @return the score (1..5). */
    public int getScore() {
        return score;
    }

    /** @return the optional comment, or {@code null}. */
    public String getComment() {
        return comment;
    }

    /** @return the rating period (e.g. {@code "2026-Q2"}). */
    public String getPeriod() {
        return period;
    }
}
