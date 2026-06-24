package com.taarifu.accountability.domain.model;

import com.taarifu.accountability.domain.model.enums.PromiseStatus;
import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A public commitment made by a representative, with a tracked status and evidence (PRD §10 Epic M6,
 * US-6.3).
 *
 * <p>Responsibility: lets citizens follow whether a promise has been kept. <b>Curated authorship
 * (D-Q4):</b> created and status-advanced by an authorised author / {@code ROLE_ADMIN} with evidence
 * ({@link #evidenceRef}) and optional links to concrete projects ({@link #linkedProjectIds}); citizens
 * read-only.</p>
 *
 * <p>WHY {@code representativeId} and {@code linkedProjectIds} are opaque {@link UUID}s, not FKs: the
 * representative lives in <b>institutions</b> and projects in the <b>projects</b> module — both off-limits
 * to import here. The {@code representativeId} existence is confirmed via institutions' published
 * {@code RepresentativeQueryApi.exists} port in {@code CurationService} before persistence (ADR-0013).
 * {@code linkedProjectIds} are not yet validated — there is no projects module/port to resolve them
 * against (// TODO(wiring): resolve via the projects API once that seam exists).</p>
 */
@Entity
@Table(name = "promise", indexes = {
        @Index(name = "ix_promise_representative", columnList = "representative_id"),
        @Index(name = "ix_promise_status", columnList = "status")
})
@SQLRestriction("deleted = false")
public class Promise extends BaseEntity {

    /**
     * Public id of the representative who made the promise (institutions module — referenced by id only,
     * never an FK; existence validated via {@code RepresentativeQueryApi.exists} in {@code CurationService}
     * before persistence).
     */
    @Column(name = "representative_id", nullable = false)
    private UUID representativeId;

    /** The text of the promise (what was committed to). */
    @Column(name = "text", nullable = false, length = 2000)
    private String text;

    /** When the promise was made. */
    @Column(name = "made_at", nullable = false)
    private LocalDate madeAt;

    /** Current tracked status (MADE/IN_PROGRESS/KEPT/BROKEN). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PromiseStatus status = PromiseStatus.MADE;

    /** Object-store reference to supporting evidence (never the bytes), or {@code null} (PRD §18). */
    @Column(name = "evidence_ref", length = 512)
    private String evidenceRef;

    /**
     * Public ids of projects this promise is linked to (projects module — referenced by id only, never
     * FKs). Stored in side table {@code promise_project} (// TODO(wiring): resolve via projects API).
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "promise_project",
            joinColumns = @JoinColumn(name = "promise_id"))
    @Column(name = "project_public_id", nullable = false)
    private Set<UUID> linkedProjectIds = new LinkedHashSet<>();

    /** JPA requires a no-arg constructor; not for application use. */
    protected Promise() {
    }

    /**
     * Creates a promise record.
     *
     * @param representativeId public id of the representative (institutions module).
     * @param text             the promise text (required).
     * @param madeAt           when the promise was made (required).
     * @param status           initial status (defaults to {@link PromiseStatus#MADE} if {@code null}).
     * @param evidenceRef      object-store evidence reference, or {@code null}.
     * @param linkedProjectIds linked project public ids, or {@code null}/empty.
     * @return the populated, transient entity.
     */
    public static Promise create(UUID representativeId, String text, LocalDate madeAt,
                                 PromiseStatus status, String evidenceRef, Set<UUID> linkedProjectIds) {
        Promise p = new Promise();
        p.representativeId = representativeId;
        p.text = text;
        p.madeAt = madeAt;
        p.status = status == null ? PromiseStatus.MADE : status;
        p.evidenceRef = evidenceRef;
        if (linkedProjectIds != null) {
            p.linkedProjectIds.addAll(linkedProjectIds);
        }
        return p;
    }

    /**
     * Advances the promise to a new tracked status (curated, evidence-backed — D-Q4).
     *
     * @param newStatus   the new status (required).
     * @param evidenceRef supporting evidence reference, or {@code null} to leave unchanged.
     */
    public void updateStatus(PromiseStatus newStatus, String evidenceRef) {
        if (newStatus != null) {
            this.status = newStatus;
        }
        if (evidenceRef != null) {
            this.evidenceRef = evidenceRef;
        }
    }

    /** @return the subject representative's public id (institutions module). */
    public UUID getRepresentativeId() {
        return representativeId;
    }

    /** @return the promise text. */
    public String getText() {
        return text;
    }

    /** @return when the promise was made. */
    public LocalDate getMadeAt() {
        return madeAt;
    }

    /** @return the current tracked status. */
    public PromiseStatus getStatus() {
        return status;
    }

    /** @return the object-store evidence reference, or {@code null}. */
    public String getEvidenceRef() {
        return evidenceRef;
    }

    /** @return an unmodifiable view of the linked project public ids. */
    public Set<UUID> getLinkedProjectIds() {
        return Set.copyOf(linkedProjectIds);
    }
}
