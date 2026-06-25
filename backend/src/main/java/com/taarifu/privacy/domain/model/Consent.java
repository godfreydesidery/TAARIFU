package com.taarifu.privacy.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.privacy.domain.model.enums.ConsentPurpose;
import com.taarifu.privacy.domain.model.enums.ConsentState;
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
 * One <b>versioned, append-on-change</b> consent decision a citizen made for a processing purpose
 * (PRD §18 PDPA 2022/2023, UC-A16, US-0.7; ADR-0016 §2).
 *
 * <p>Responsibility: the lawful-basis evidence record. A grant or a withdrawal for a (subject, purpose)
 * is a <b>new row</b>; the prior current row for that pair is marked {@link #superseded}. The <i>current</i>
 * consent for a (subject, purpose) is the single non-superseded row. WHY append-on-change rather than
 * mutate-in-place: consent <b>history</b> is itself the evidence PDPA requires — when consent was granted,
 * when withdrawn, under which policy version — and must be reconstructable; mutating a row would destroy
 * that trail (the same philosophy as the immutable audit log, §25.1).</p>
 *
 * <p><b>🔒 No PII (PRD §18):</b> this row holds only the subject's own <b>account public id</b> (an opaque
 * UUID — a cross-module reference to {@code identity}, deliberately <b>not</b> a foreign key, ADR-0013 §3.2),
 * the purpose, the state, the policy version, timestamps, and a coarse {@code source} channel. No name,
 * phone, ID, or free text ever enters this table.</p>
 *
 * <p>Reads filter {@code deleted = false} via {@code @SQLRestriction}; consent rows are never user-deletable
 * (a withdrawal is a new {@code WITHDRAWN} row, not a delete), but the soft-delete column comes with
 * {@link BaseEntity} and is reserved for retention/erasure tooling.</p>
 */
@Entity
@Table(name = "consent", indexes = {
        // The hot read: "the current consent for this subject + purpose" filters subject + purpose + not-superseded.
        @Index(name = "ix_consent_subject_purpose", columnList = "subject_public_id, purpose, superseded"),
        @Index(name = "ix_consent_subject", columnList = "subject_public_id")
})
@SQLRestriction("deleted = false")
public class Consent extends BaseEntity {

    /**
     * The consenting account's public id (the JWT-subject grain). A bare cross-module reference to
     * {@code identity} — never an FK (ADR-0013 §3.2). This is the subject's <b>own</b> id; consent is
     * always self-asserted.
     */
    @Column(name = "subject_public_id", nullable = false)
    private UUID subjectPublicId;

    /** The processing purpose this decision is about (governed enum). */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 48)
    private ConsentPurpose purpose;

    /** Whether the subject granted or withdrew consent for {@link #purpose}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ConsentState state;

    /**
     * The privacy-policy / consent-text version this decision was made against, so a policy change can
     * re-prompt and stale grants are distinguishable (PDPA — informed consent). Never PII.
     */
    @Column(name = "policy_version", nullable = false, length = 32)
    private String policyVersion;

    /** Coarse capture channel ({@code WEB}/{@code APP}/{@code USSD}); diagnostics only, never PII. */
    @Column(name = "source", length = 16)
    private String source;

    /** When a {@link ConsentState#GRANTED} decision was made (UTC); {@code null} for a withdrawal row. */
    @Column(name = "granted_at")
    private Instant grantedAt;

    /** When a {@link ConsentState#WITHDRAWN} decision was made (UTC); {@code null} for a grant row. */
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    /**
     * {@code true} once a later decision for the same (subject, purpose) has superseded this row — the
     * marker that makes "the current consent" a single-row lookup. The latest decision is always
     * {@code superseded = false}.
     */
    @Column(name = "superseded", nullable = false)
    private boolean superseded = false;

    /** JPA requires a no-arg constructor; application code uses {@link #record}. */
    protected Consent() {
    }

    /**
     * Builds a new (non-superseded) consent decision row.
     *
     * @param subjectPublicId the consenting account's public id.
     * @param purpose         the processing purpose.
     * @param state           granted or withdrawn.
     * @param policyVersion   the policy/consent-text version (never blank — informed consent).
     * @param source          the coarse capture channel, or {@code null}.
     * @param now             the decision instant (UTC, from the injected clock).
     * @return the populated, transient consent row.
     */
    public static Consent record(UUID subjectPublicId, ConsentPurpose purpose, ConsentState state,
                                 String policyVersion, String source, Instant now) {
        Consent c = new Consent();
        c.subjectPublicId = subjectPublicId;
        c.purpose = purpose;
        c.state = state;
        c.policyVersion = policyVersion;
        c.source = source;
        if (state == ConsentState.GRANTED) {
            c.grantedAt = now;
        } else {
            c.withdrawnAt = now;
        }
        return c;
    }

    /**
     * Marks this row superseded by a later decision for the same (subject, purpose). Append-on-change:
     * the value of the prior decision is preserved; only this flag flips so the latest row is the single
     * current one.
     */
    public void markSuperseded() {
        this.superseded = true;
    }

    /** @return the consenting account's public id. */
    public UUID getSubjectPublicId() {
        return subjectPublicId;
    }

    /** @return the processing purpose. */
    public ConsentPurpose getPurpose() {
        return purpose;
    }

    /** @return granted vs withdrawn. */
    public ConsentState getState() {
        return state;
    }

    /** @return the policy/consent-text version this decision was made against. */
    public String getPolicyVersion() {
        return policyVersion;
    }

    /** @return the coarse capture channel, or {@code null}. */
    public String getSource() {
        return source;
    }

    /** @return when consent was granted (UTC), or {@code null} for a withdrawal row. */
    public Instant getGrantedAt() {
        return grantedAt;
    }

    /** @return when consent was withdrawn (UTC), or {@code null} for a grant row. */
    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    /** @return whether a later decision has superseded this row (so it is no longer the current one). */
    public boolean isSuperseded() {
        return superseded;
    }
}
