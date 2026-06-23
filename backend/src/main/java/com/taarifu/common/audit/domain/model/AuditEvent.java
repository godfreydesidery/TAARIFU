package com.taarifu.common.audit.domain.model;

import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable, append-only security/audit record (AUTH-DESIGN §11, ADR-0011 §8, L-1).
 *
 * <p>Responsibility: one row per security-relevant decision (auth, authz, identity lifecycle). It is
 * deliberately <b>not</b> a {@link com.taarifu.common.domain.model.BaseEntity} subclass: those carry
 * "who last touched this row" + soft-delete, whereas an audit event is <b>never updated or deleted</b>
 * — it has no {@code version}, no {@code updated_*}, no {@code deleted} columns. Erasure appends a new
 * {@link AuditEventType#IDENTITY_ERASED} tombstone; history is never mutated (§25.1). The application is
 * granted only {@code INSERT}+{@code SELECT} on this table (the DDL the database engineer adds revokes
 * {@code UPDATE}/{@code DELETE}).</p>
 *
 * <p>Privacy invariant (PRD §18, PDPA): this row holds <b>references/hashes only — never raw PII</b>.
 * The actor/subject are {@code publicId}s (UUIDs), the client IP is stored <b>hashed</b>, and any rich
 * detail is an object-store {@link #detailRef}, never inline. No phone, {@code idNo}, OTP value, or raw
 * token is ever written here.</p>
 *
 * <p>WHY a {@link #prevHash}/{@link #entryHash} pair: an optional hash-chain makes the log
 * tamper-evident — {@code entryHash = H(prevHash ∥ canonical(row))} — so a deleted/edited row breaks
 * the chain. The chain is best-effort and computed by the writer.</p>
 */
@Entity
@Table(name = "audit_event", indexes = {
        @Index(name = "ix_audit_event_actor", columnList = "actor_public_id"),
        @Index(name = "ix_audit_event_subject", columnList = "subject_public_id"),
        @Index(name = "ix_audit_event_type_time", columnList = "event_type, occurred_at"),
        @Index(name = "ix_audit_event_correlation", columnList = "correlation_id")
})
public class AuditEvent {

    /** Internal surrogate PK (append-only; never exposed). */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** Public, non-enumerable id of the event. */
    @Column(name = "public_id", updatable = false, nullable = false, unique = true)
    private UUID publicId;

    /** Server-side event instant (UTC). */
    @Column(name = "occurred_at", updatable = false, nullable = false)
    private Instant occurredAt;

    /** The catalogued event type. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", updatable = false, nullable = false, length = 64)
    private AuditEventType eventType;

    /**
     * {@code publicId} of the acting principal; the {@code SYSTEM_ACTOR} sentinel for system events;
     * {@code null} for an unauthenticated/anonymous actor (S-5 — never a spoofable id).
     */
    @Column(name = "actor_public_id", updatable = false)
    private UUID actorPublicId;

    /** {@code publicId} of the entity/account acted upon (e.g. the account being verified). */
    @Column(name = "subject_public_id", updatable = false)
    private UUID subjectPublicId;

    /** Role names active at action time (multi-hat audit, D16); comma-separated, never PII. */
    @Column(name = "actor_roles", updatable = false, length = 255)
    private String actorRoles;

    /** The outcome dimension (success/failure/denied). */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", updatable = false, nullable = false, length = 16)
    private AuditOutcome outcome;

    /** Machine reason (e.g. {@code INVALID_CREDENTIALS}, {@code REUSE_DETECTED}); never PII. */
    @Column(name = "reason_code", updatable = false, length = 64)
    private String reasonCode;

    /** <b>Hashed</b> client IP — never the raw IP (PDPA, S-5). */
    @Column(name = "client_ip_hash", updatable = false, length = 64)
    private String clientIpHash;

    /** Request/trace correlation id joining this event to logs/traces. */
    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    /** Object-store key / reference to non-PII detail; <b>never</b> inline PII. */
    @Column(name = "detail_ref", updatable = false, length = 512)
    private String detailRef;

    /** Previous entry's {@link #entryHash} for the tamper-evident chain, or {@code null} for the first. */
    @Column(name = "prev_hash", updatable = false, length = 64)
    private String prevHash;

    /** This entry's chain hash {@code = H(prevHash ∥ canonical(row))}. */
    @Column(name = "entry_hash", updatable = false, length = 64)
    private String entryHash;

    /** JPA requires a no-arg constructor; application code uses {@link Builder}. */
    protected AuditEvent() {
    }

    /** Assigns the public id and event instant before insert if not already set. */
    @PrePersist
    void assignDefaults() {
        if (this.publicId == null) {
            this.publicId = UUID.randomUUID();
        }
        if (this.occurredAt == null) {
            this.occurredAt = Instant.now();
        }
    }

    /** @return the internal PK (never serialise). */
    public Long getId() {
        return id;
    }

    /** @return the public event id. */
    public UUID getPublicId() {
        return publicId;
    }

    /** @return the event instant (UTC). */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** @return the catalogued event type. */
    public AuditEventType getEventType() {
        return eventType;
    }

    /** @return the actor {@code publicId}, or {@code null} for anonymous. */
    public UUID getActorPublicId() {
        return actorPublicId;
    }

    /** @return the subject {@code publicId}, or {@code null}. */
    public UUID getSubjectPublicId() {
        return subjectPublicId;
    }

    /** @return the comma-separated active role names, or {@code null}. */
    public String getActorRoles() {
        return actorRoles;
    }

    /** @return the outcome. */
    public AuditOutcome getOutcome() {
        return outcome;
    }

    /** @return the machine reason code, or {@code null}. */
    public String getReasonCode() {
        return reasonCode;
    }

    /** @return the hashed client IP, or {@code null}. */
    public String getClientIpHash() {
        return clientIpHash;
    }

    /** @return the correlation id, or {@code null}. */
    public UUID getCorrelationId() {
        return correlationId;
    }

    /** @return the non-PII detail reference, or {@code null}. */
    public String getDetailRef() {
        return detailRef;
    }

    /** @return the previous chain hash, or {@code null}. */
    public String getPrevHash() {
        return prevHash;
    }

    /** @return this entry's chain hash. */
    public String getEntryHash() {
        return entryHash;
    }

    /**
     * Sets the tamper-evidence chain hashes. Writer-only by convention (called by
     * {@code AuditEventWriter} just before persist); not part of the public mutation surface.
     *
     * @param prevHash  the previous entry's hash, or {@code null} for the first event.
     * @param entryHash this entry's computed chain hash.
     */
    public void setChain(String prevHash, String entryHash) {
        this.prevHash = prevHash;
        this.entryHash = entryHash;
    }

    /**
     * Fluent builder for an {@link AuditEvent}.
     *
     * <p>WHY a builder (not a constructor): an audit event has many optional reference fields
     * (subject, roles, reason, ip-hash, correlation, detail) and assembling it positionally would be
     * error-prone; the builder keeps each call site self-documenting and PII-safe by construction.</p>
     */
    public static final class Builder {
        private final AuditEvent e = new AuditEvent();

        private Builder(AuditEventType eventType, AuditOutcome outcome) {
            e.eventType = eventType;
            e.outcome = outcome;
        }

        /**
         * @param eventType the catalogued type.
         * @param outcome   the outcome dimension.
         * @return a new builder.
         */
        public static Builder of(AuditEventType eventType, AuditOutcome outcome) {
            return new Builder(eventType, outcome);
        }

        /** @param actorPublicId the acting principal's public id (or {@code null} for anonymous). */
        public Builder actor(UUID actorPublicId) {
            e.actorPublicId = actorPublicId;
            return this;
        }

        /** @param subjectPublicId the public id of the entity acted upon. */
        public Builder subject(UUID subjectPublicId) {
            e.subjectPublicId = subjectPublicId;
            return this;
        }

        /** @param actorRoles comma-separated active role names. */
        public Builder roles(String actorRoles) {
            e.actorRoles = actorRoles;
            return this;
        }

        /** @param reasonCode the machine reason (never PII). */
        public Builder reason(String reasonCode) {
            e.reasonCode = reasonCode;
            return this;
        }

        /** @param clientIpHash the <b>hashed</b> client IP (never raw). */
        public Builder clientIpHash(String clientIpHash) {
            e.clientIpHash = clientIpHash;
            return this;
        }

        /** @param correlationId the request/trace id. */
        public Builder correlation(UUID correlationId) {
            e.correlationId = correlationId;
            return this;
        }

        /** @param detailRef an object-store key for non-PII detail (never inline PII). */
        public Builder detailRef(String detailRef) {
            e.detailRef = detailRef;
            return this;
        }

        /** @return the assembled (still transient) event. */
        public AuditEvent build() {
            return e;
        }
    }
}
