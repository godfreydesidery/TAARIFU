package com.taarifu.engagement.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.engagement.domain.model.enums.PetitionStatus;
import com.taarifu.engagement.domain.model.enums.PetitionTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * A citizen/organisation petition addressed to a representative or office (PRD §9.1, §12.2 M9).
 *
 * <p>Responsibility: the petition aggregate — its text, its {@link #targetType}/{@link #targetId}
 * (the addressee), a {@link #signatureGoal} threshold, a {@link #deadline}, and its lifecycle
 * {@link #status}. Signatures are a separate aggregate ({@link PetitionSignature}); the
 * {@link #signatureCount} here is a derived, denormalised counter maintained atomically when a
 * signature is added (kept on the petition row so the public list/threshold read is O(1) and never
 * scans the signature table).</p>
 *
 * <p>WHY the target is a {@code UUID} + {@link PetitionTargetType}, not a real FK: the addressee (a
 * Representative or an Office) is owned by the <b>institutions</b> module, which this module must not
 * import (HARD ISOLATION rule 2). The reference is therefore late-bound by id; resolving/validating the
 * target against the institutions registry is a later integration step
 * (// TODO(wiring) in the service).</p>
 *
 * <p>WHY {@link #creatorProfileId} / {@link #creatorOrgId} are {@code UUID}s, not FKs to identity: same
 * boundary discipline — the creator is an identity {@code Profile} (person) or organisation; we reference
 * it by its public id rather than FK-coupling engagement to identity's tables. Exactly one of the two is
 * set (a person-authored or an org-authored petition).</p>
 *
 * <p><b>Integrity (D13/D16):</b> a representative may not petition against <i>themselves</i>; that
 * conflict-of-interest guard is enforced in the application service via the shared
 * {@link com.taarifu.common.security.ScopeGuard} seam, audited, not in this entity.</p>
 */
@Entity
@Table(name = "petition", indexes = {
        @Index(name = "ix_petition_status", columnList = "status"),
        @Index(name = "ix_petition_target", columnList = "target_type, target_id"),
        @Index(name = "ix_petition_creator_profile", columnList = "creator_profile_id"),
        @Index(name = "ix_petition_creator_org", columnList = "creator_org_id")
})
@SQLRestriction("deleted = false")
public class Petition extends BaseEntity {

    /** Short headline of the petition (e.g. "Repair the Kata road"). */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** The petition body / ask (the case being made). */
    @Column(name = "body", nullable = false, length = 8000)
    private String body;

    /** Whether the addressee is a representative or an office (discriminates {@link #targetId}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private PetitionTargetType targetType;

    /**
     * Public id of the addressee in the <b>institutions</b> module (Representative or Office). Reference
     * by id only — engagement never imports institutions (HARD ISOLATION rule 2). May be {@code null}
     * only transiently in DRAFT.
     */
    @Column(name = "target_id")
    private UUID targetId;

    /** Number of signatures that marks the petition {@code SUCCEEDED} and notifies the target (UC-E04). */
    @Column(name = "signature_goal", nullable = false)
    private int signatureGoal;

    /**
     * Denormalised, atomically-maintained count of valid signatures. WHY stored here: the public list and
     * the threshold check must not scan {@code petition_signature}; one counter on the row keeps those
     * reads O(1). It is incremented in the same transaction that inserts the signature (one-per-person is
     * still guaranteed by the signature unique index — this counter never authorises an action).
     */
    @Column(name = "signature_count", nullable = false)
    private int signatureCount = 0;

    /** Optional deadline; on/after this date the petition may be {@code CLOSED} (PRD §12.2). */
    @Column(name = "deadline")
    private Instant deadline;

    /** Public id of the authoring person's identity {@code Profile}, or {@code null} for an org petition. */
    @Column(name = "creator_profile_id")
    private UUID creatorProfileId;

    /** Public id of the authoring organisation, or {@code null} for a person petition. */
    @Column(name = "creator_org_id")
    private UUID creatorOrgId;

    /** Current lifecycle state (PRD §12.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PetitionStatus status = PetitionStatus.DRAFT;

    /** The target's published response, set when the petition is {@code RESPONDED} (UC-E05); else {@code null}. */
    @Column(name = "response", length = 8000)
    private String response;

    /** JPA requires a no-arg constructor; application code uses {@link #create}. */
    protected Petition() {
    }

    /**
     * Creates a new petition in {@code DRAFT}. The signature count starts at zero; moderation moves it to
     * {@code ACTIVE} before it is publicly visible (UC-E02).
     *
     * @param title            the headline.
     * @param body             the ask.
     * @param targetType       representative vs office.
     * @param targetId         the addressee's public id (institutions module; by-id only).
     * @param signatureGoal    the success threshold (must be positive — validated at the edge).
     * @param deadline         optional deadline, or {@code null}.
     * @param creatorProfileId authoring person's profile public id, or {@code null} if org-authored.
     * @param creatorOrgId     authoring organisation public id, or {@code null} if person-authored.
     * @return the populated, transient petition (status {@code DRAFT}).
     */
    public static Petition create(String title, String body, PetitionTargetType targetType, UUID targetId,
                                  int signatureGoal, Instant deadline,
                                  UUID creatorProfileId, UUID creatorOrgId) {
        Petition p = new Petition();
        p.title = title;
        p.body = body;
        p.targetType = targetType;
        p.targetId = targetId;
        p.signatureGoal = signatureGoal;
        p.deadline = deadline;
        p.creatorProfileId = creatorProfileId;
        p.creatorOrgId = creatorOrgId;
        p.status = PetitionStatus.DRAFT;
        p.signatureCount = 0;
        return p;
    }

    /**
     * Records one more signature and advances the lifecycle to {@code SUCCEEDED} if the goal is reached.
     *
     * <p>WHY the counter increment lives here (not the service): keeping the derived count consistent with
     * its threshold transition in one method makes the invariant "count ≥ goal ⇒ SUCCEEDED" local and
     * testable. The caller increments inside the same transaction that inserts the {@link PetitionSignature};
     * one-per-person is enforced by the DB unique index, so this never double-counts.</p>
     */
    public void registerSignature() {
        this.signatureCount += 1;
        if (this.status == PetitionStatus.ACTIVE && this.signatureCount >= this.signatureGoal) {
            this.status = PetitionStatus.SUCCEEDED;
        }
    }

    /** Marks the petition {@code ACTIVE} (post-moderation, UC-E02). */
    public void activate() {
        this.status = PetitionStatus.ACTIVE;
    }

    /** @return whether the petition is publicly visible (anything past DRAFT). */
    public boolean isPubliclyVisible() {
        return this.status != PetitionStatus.DRAFT;
    }

    /** @return the headline. */
    public String getTitle() {
        return title;
    }

    /** @return the body/ask. */
    public String getBody() {
        return body;
    }

    /** @return representative vs office target discriminator. */
    public PetitionTargetType getTargetType() {
        return targetType;
    }

    /** @return the addressee's public id (institutions module), or {@code null} in draft. */
    public UUID getTargetId() {
        return targetId;
    }

    /** @return the success threshold. */
    public int getSignatureGoal() {
        return signatureGoal;
    }

    /** @return the current valid-signature count (derived, denormalised). */
    public int getSignatureCount() {
        return signatureCount;
    }

    /** @return the deadline, or {@code null}. */
    public Instant getDeadline() {
        return deadline;
    }

    /** @return the authoring person's profile public id, or {@code null}. */
    public UUID getCreatorProfileId() {
        return creatorProfileId;
    }

    /** @return the authoring organisation public id, or {@code null}. */
    public UUID getCreatorOrgId() {
        return creatorOrgId;
    }

    /** @return the current lifecycle state. */
    public PetitionStatus getStatus() {
        return status;
    }

    /** @return the target's published response, or {@code null}. */
    public String getResponse() {
        return response;
    }
}
