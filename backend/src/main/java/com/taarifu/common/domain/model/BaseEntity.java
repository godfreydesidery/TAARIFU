package com.taarifu.common.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared persistence base for every Taarifu entity (ARCHITECTURE.md §4.2, ADR-0006).
 *
 * <p>Responsibility: gives every persistent aggregate one disciplined identity + audit +
 * soft-delete + optimistic-lock shape, replacing the legacy {@code id + uid + code} triple
 * and loose {@code Long} foreign-id fields (PRD §6.3).</p>
 *
 * <p>Identity model (ADR-0006):</p>
 * <ul>
 *   <li>{@link #id} — internal {@code Long} surrogate PK used for <b>real foreign keys</b> and
 *       joins. <b>Never exposed</b> in any API or DTO (enumeration/volume leak — PRD §17).</li>
 *   <li>{@link #publicId} — the {@code UUID} that appears in URLs/DTOs. Generated application-side
 *       on persist so it is present before the INSERT returns; column is {@code unique, not null}.</li>
 * </ul>
 *
 * <p>WHY soft-delete via {@code deleted} columns rather than physical {@code DELETE}: civic and
 * audit records must remain referentially intact and reconstructable (PRD §9, §18). Concrete
 * entities are expected to declare {@code @SQLRestriction("deleted = false")} so default repository
 * queries hide tombstoned rows; this base only carries the columns (DRY).</p>
 *
 * <p>WHY no Lombok here: this is a load-bearing security/audit base; explicit accessors keep it
 * unambiguous and reviewable (CLAUDE.md §8 — Lombok sparingly).</p>
 *
 * <p>Subclasses map their own table; this class is a {@link MappedSuperclass} (no table of its own).
 * Audit fields are populated by Spring Data JPA auditing wired in {@code TaarifuApplication} +
 * {@link com.taarifu.common.persistence.AuditorAwareImpl}.</p>
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    /**
     * Internal surrogate primary key. Database-generated (IDENTITY/sequence per the Flyway DDL).
     * Used only for FKs/joins inside the backend; never serialised to clients.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /**
     * Public, non-enumerable identifier exposed in URLs and DTOs (PRD §17, ADR-0006).
     * Assigned in {@link #assignPublicId()} on first persist if not already set.
     */
    @Column(name = "public_id", updatable = false, nullable = false, unique = true)
    private UUID publicId;

    /** Optimistic-lock version; a stale value yields {@code 409 CONFLICT} (PRD §17). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Audit: creation instant (UTC), set once by JPA auditing. */
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    /** Audit: {@code publicId} of the actor who created the row (system actor pre-auth). */
    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** Audit: last-modification instant (UTC), maintained by JPA auditing. */
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Audit: {@code publicId} of the actor who last modified the row. */
    @LastModifiedBy
    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Soft-delete flag; {@code true} tombstones the row (PRD §9, §18). Defaults to {@code false}. */
    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    /** Soft-delete instant (UTC); {@code null} unless {@link #deleted} is {@code true}. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Soft-delete actor {@code publicId}; {@code null} unless {@link #deleted} is {@code true}. */
    @Column(name = "deleted_by")
    private UUID deletedBy;

    /**
     * Ensures a {@link #publicId} exists before the row is inserted.
     *
     * <p>WHY application-side generation: the public id must be known to the caller in the same
     * request that creates the entity (e.g. to build the {@code Location} URL) without a round-trip,
     * and must not depend on a DB default (ADR-0006). A v4 {@code UUID} is used here for the
     * scaffold; the production code may swap to a time-ordered (v7/ULID) generator for index
     * locality without changing this contract.</p>
     */
    @PrePersist
    void assignPublicId() {
        if (this.publicId == null) {
            this.publicId = UUID.randomUUID();
        }
    }

    /** @return the internal numeric PK (backend-only; never serialise). */
    public Long getId() {
        return id;
    }

    /** @return the public {@code UUID} used in URLs/DTOs. */
    public UUID getPublicId() {
        return publicId;
    }

    /** @return the optimistic-lock version. */
    public Long getVersion() {
        return version;
    }

    /** @return creation instant (UTC), or {@code null} before first persist. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return {@code publicId} of the creating actor. */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /** @return last-modification instant (UTC). */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** @return {@code publicId} of the last-modifying actor. */
    public UUID getUpdatedBy() {
        return updatedBy;
    }

    /** @return {@code true} if this row is soft-deleted (tombstoned). */
    public boolean isDeleted() {
        return deleted;
    }

    /** @return soft-delete instant (UTC), or {@code null}. */
    public Instant getDeletedAt() {
        return deletedAt;
    }

    /** @return soft-delete actor {@code publicId}, or {@code null}. */
    public UUID getDeletedBy() {
        return deletedBy;
    }

    /**
     * Marks this entity as soft-deleted. Callers set the deleting actor's {@code publicId}.
     *
     * @param actorPublicId the {@code publicId} of the actor performing the deletion.
     */
    public void markDeleted(UUID actorPublicId) {
        this.deleted = true;
        this.deletedAt = Instant.now();
        this.deletedBy = actorPublicId;
    }
}
