package com.taarifu.accountability.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A representative's <b>right-of-reply</b> to a citizen rating about them — the D-rated-fairness rule
 * (PRD &sect;10 Epic M6, US-6.2; PDPA fairness/&sect;18).
 *
 * <p>Responsibility: lets the rated representative respond, in their own words, to a single rating, so the
 * public aggregate is never one-sided — the reply is shown <i>with</i> the rating it answers. This is the
 * fairness counterweight to the binding rating: a citizen rates; the representative gets exactly one reply.</p>
 *
 * <p><b>The one-per-rating fairness cap (a hard DB unique):</b> {@code ux_rating_reply_one_per_rating}
 * makes a second reply to the same rating a hard {@code 409}. A representative gets a reply, not a thread.
 * Editing the reply is the representative's own follow-up ({@link #revise}); posting a <i>new</i> one is
 * blocked.</p>
 *
 * <p><b>The ownership/conflict-of-interest fence (enforced in {@code RatingReplyService}, not here):</b> a
 * representative may reply <b>only</b> to a rating whose subject is themselves — a rep cannot reply to a
 * rating about a rival. The service resolves "is the replying account the linked account of the rated
 * representative?" via the {@code RepresentativeOwnershipPort} before persist. This entity is a pure record
 * and performs no authorization (the service owns the fence — SOLID single-responsibility, mirroring
 * {@link Rating}).</p>
 *
 * <p><b>WHY a real {@code @OneToOne} FK to {@link Rating}</b> (not an opaque id): the rating lives in
 * <b>this</b> module, so a foreign key is correct and enforced (the cross-module "reference by id, never an
 * FK" rule applies only across module boundaries — ARCHITECTURE &sect;4.3). The FK + unique together
 * guarantee a reply can never be orphaned and never duplicated for a rating.</p>
 *
 * <p><b>WHY {@code authorAccountId} is an opaque {@link UUID}:</b> the replying principal's account lives in
 * <b>identity</b> (off-limits to import). It is captured for the audit trail; it is never the request body's
 * claim (the service takes it from the security context).</p>
 */
@Entity
@Table(name = "rating_reply",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_rating_reply_one_per_rating",
                columnNames = "rating_id"),
        indexes = {
                @Index(name = "ix_rating_reply_author", columnList = "author_account_id")
        })
@SQLRestriction("deleted = false")
public class RatingReply extends BaseEntity {

    /**
     * The rating this reply answers (same-module aggregate — a real, enforced FK). The {@code @OneToOne} +
     * the {@code rating_id} unique together pin exactly one reply per rating.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rating_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_rating_reply_rating"))
    private Rating rating;

    /**
     * Public id of the representative the answered rating is about (institutions module — referenced by id
     * only, never an FK). Always equals {@code rating.getSubjectId()} at create time (a REPRESENTATIVE subject).
     */
    @Column(name = "representative_id", nullable = false)
    private UUID representativeId;

    /**
     * Public id of the account that authored the reply (identity module — referenced by id only, never an FK):
     * the representative's own linked account (a SELF reply) or an ADMIN/ROOT curator (a CURATED on-behalf
     * reply). Taken from the security context, never the request body.
     */
    @Column(name = "author_account_id", nullable = false)
    private UUID authorAccountId;

    /** {@code true} if a curator posted this on the representative's behalf (D-Q4); {@code false} for a self-reply. */
    @Column(name = "on_behalf", nullable = false)
    private boolean onBehalf;

    /** The reply text (the representative's response). Moderated downstream like any citizen-visible content. */
    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    /** JPA requires a no-arg constructor; not for application use. */
    protected RatingReply() {
    }

    /**
     * Creates a right-of-reply record. The caller MUST have already passed the ownership fence — this factory
     * is a pure constructor and performs no authorization (the service owns the fence).
     *
     * @param rating           the rating being answered (required; same-module FK).
     * @param representativeId the rated representative's public id (= {@code rating.getSubjectId()}).
     * @param authorAccountId  the replying account's public id (from the security context).
     * @param onBehalf         {@code true} for a curator on-behalf reply, {@code false} for a self-reply.
     * @param body             the reply text (required).
     * @return the populated, transient entity.
     */
    public static RatingReply create(Rating rating, UUID representativeId, UUID authorAccountId,
                                     boolean onBehalf, String body) {
        RatingReply r = new RatingReply();
        r.rating = rating;
        r.representativeId = representativeId;
        r.authorAccountId = authorAccountId;
        r.onBehalf = onBehalf;
        r.body = body;
        return r;
    }

    /**
     * Updates the reply text in place — the representative's (or curator's) own follow-up edit. A <i>new</i>
     * reply is blocked by the one-per-rating unique; editing the existing one is allowed.
     *
     * @param body the new reply text (required).
     */
    public void revise(String body) {
        this.body = body;
    }

    /** @return the rating this reply answers (same-module aggregate). */
    public Rating getRating() {
        return rating;
    }

    /** @return the rated representative's public id (institutions module). */
    public UUID getRepresentativeId() {
        return representativeId;
    }

    /** @return the replying account's public id (identity module). */
    public UUID getAuthorAccountId() {
        return authorAccountId;
    }

    /** @return {@code true} if posted by a curator on the representative's behalf. */
    public boolean isOnBehalf() {
        return onBehalf;
    }

    /** @return the reply text. */
    public String getBody() {
        return body;
    }
}
