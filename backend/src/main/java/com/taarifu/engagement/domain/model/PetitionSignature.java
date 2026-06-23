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
 * One person's signature on a {@link Petition} — a <b>binding civic act</b> (PRD §9.1, §12.2 UC-E03,
 * §23.5 integrity fence).
 *
 * <p>Responsibility: records that a specific signer (an identity {@code Profile}, referenced by
 * {@link #signerProfileId} — by id only, never an FK into identity) signed a specific petition, with an
 * optional comment and a privacy choice. The defining invariant is <b>one signature per person per
 * petition</b>, enforced in the database by a composite unique constraint on
 * {@code (petition_id, signer_profile_id)} scoped to live rows.</p>
 *
 * <p><b>Civic-integrity fence (D18, PRD §23.5):</b> a signature is a democratic-weight act. Authorising
 * it checks <b>tier (T3) + electoral scope + one-per-person</b> only and must <b>never</b> read a token
 * balance. The T3 gate is the {@code @RequiresTier("T3")} on the sign endpoint (re-resolved live by
 * {@code RequiresTierAspect}); the one-per-person guarantee is <b>this unique constraint</b>; the
 * electoral-scope check and the no-self-petition conflict guard (D13/D16) are applied in the service via
 * the shared {@link com.taarifu.common.security.ScopeGuard} seam (electoral-scope enforcement is wired in
 * integration — see the service // TODO(wiring)). The unique constraint is the load-bearing
 * "one person = one signature regardless of token balance" rule (PRD §23.5).</p>
 *
 * <p>WHY the petition side is a real FK but the signer is a UUID: the petition is owned by this very
 * module, so a real FK is correct and gives referential integrity for the count; the signer is owned by
 * identity, so it is referenced by public id to keep the module boundary (ARCHITECTURE §3.2).</p>
 */
@Entity
@Table(name = "petition_signature",
        uniqueConstraints = {
                // ONE signature per (petition, signer) — the one-person-one-signature invariant (UC-E03).
                @UniqueConstraint(name = "uq_petition_signature_once",
                        columnNames = {"petition_id", "signer_profile_id"})
        },
        indexes = {
                @Index(name = "ix_petition_signature_petition", columnList = "petition_id"),
                @Index(name = "ix_petition_signature_signer", columnList = "signer_profile_id")
        })
@SQLRestriction("deleted = false")
public class PetitionSignature extends BaseEntity {

    /** The petition signed (real FK — same module). Many signatures per petition. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "petition_id", nullable = false)
    private Petition petition;

    /**
     * Public id of the signing identity {@code Profile} (by id only — no FK into identity). With
     * {@link #petition} this forms the one-per-person unique key.
     */
    @Column(name = "signer_profile_id", nullable = false)
    private UUID signerProfileId;

    /** Optional comment the signer attached (UC-E03 "optional comment"). */
    @Column(name = "comment", length = 1000)
    private String comment;

    /**
     * Whether the signer chose to be shown publicly. Default {@code false} (private) so a citizen is never
     * exposed without an explicit opt-in (PDPA data-minimisation, PRD §18). The signature still counts
     * toward the goal regardless of this flag.
     */
    @Column(name = "is_public", nullable = false)
    private boolean publicSignature = false;

    /** JPA requires a no-arg constructor; application code uses {@link #of}. */
    protected PetitionSignature() {
    }

    /**
     * Builds a signature row. The one-per-person guarantee is the DB unique constraint's job; this factory
     * only constructs the row (the service inserts it and atomically bumps {@link Petition#registerSignature()}).
     *
     * @param petition         the petition being signed (real FK).
     * @param signerProfileId  the signer's identity {@code Profile} public id (by id only).
     * @param comment          optional comment, or {@code null}.
     * @param publicSignature  whether to show the signer publicly (opt-in; default private).
     * @return the populated, transient signature row.
     */
    public static PetitionSignature of(Petition petition, UUID signerProfileId, String comment,
                                       boolean publicSignature) {
        PetitionSignature s = new PetitionSignature();
        s.petition = petition;
        s.signerProfileId = signerProfileId;
        s.comment = comment;
        s.publicSignature = publicSignature;
        return s;
    }

    /** @return the signed petition. */
    public Petition getPetition() {
        return petition;
    }

    /** @return the signer's profile public id. */
    public UUID getSignerProfileId() {
        return signerProfileId;
    }

    /** @return the optional comment, or {@code null}. */
    public String getComment() {
        return comment;
    }

    /** @return whether the signer opted to be shown publicly. */
    public boolean isPublicSignature() {
        return publicSignature;
    }
}
