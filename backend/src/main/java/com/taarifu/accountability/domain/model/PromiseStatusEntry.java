package com.taarifu.accountability.domain.model;

import com.taarifu.accountability.domain.model.enums.PromiseStatus;
import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.SQLRestriction;

/**
 * One immutable, citizen-visible entry in a {@link Promise}'s status timeline — the append-only history of
 * how a representative's commitment has moved (made -&gt; in-progress -&gt; kept/broken) over time
 * (PRD &sect;10 Epic M6, US-6.3 "citizen-visible timeline").
 *
 * <p>Responsibility: capture <b>each</b> status transition (and the originating MADE state) as its own row,
 * so a citizen sees not just the <i>current</i> status of a promise but the full provenance of how it got
 * there. {@link Promise#getStatus()} stays the denormalised "latest" for cheap list filtering; this timeline
 * is the audit-grade record behind it.</p>
 *
 * <p><b>WHY append-only (no update/delete API):</b> a promise timeline is an accountability record — rewriting
 * history would let a status judgement be silently changed, defeating the point (election-period neutrality,
 * PRD &sect;10). Each curated move (D-Q4) appends a new entry; the entity exposes a factory and read-only
 * getters only. A {@code KEPT}/{@code BROKEN} entry is a deliberate, provenance-bearing decision
 * ({@link #evidenceRef} + {@link #note}), never an inferred default.</p>
 *
 * <p><b>WHY a real {@code @ManyToOne} FK to {@link Promise}</b> (not an opaque id): {@code Promise} lives in
 * <b>this</b> module, so a foreign key is correct and enforced — the cross-module "reference by id, never an
 * FK" rule applies only across module boundaries (ARCHITECTURE &sect;4.3). The FK guarantees a timeline entry
 * can never be orphaned from its promise.</p>
 */
@Entity
@Table(name = "promise_status_entry", indexes = {
        @Index(name = "ix_promise_status_entry_promise", columnList = "promise_id")
})
@Check(name = "ck_promise_status_entry_status",
        constraints = "status IN ('MADE','IN_PROGRESS','KEPT','BROKEN')")
@SQLRestriction("deleted = false")
public class PromiseStatusEntry extends BaseEntity {

    /**
     * The promise this entry belongs to (same-module aggregate — a real, enforced FK). LAZY: the timeline is
     * read by promise, so the parent is already known and need not be eagerly loaded.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promise_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_promise_status_entry_promise"))
    private Promise promise;

    /** The status the promise moved <b>to</b> at this point in the timeline. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PromiseStatus status;

    /**
     * Object-store reference to the evidence backing this transition (never the bytes), or {@code null}
     * (PRD &sect;18). A {@code KEPT}/{@code BROKEN} move is expected to carry evidence (D-Q4 curated authorship).
     */
    @Column(name = "evidence_ref", length = 512)
    private String evidenceRef;

    /** Optional short curator note explaining the transition (e.g. "dispensary opened 2026-05"), or {@code null}. */
    @Column(name = "note", length = 1000)
    private String note;

    /** JPA requires a no-arg constructor; not for application use. */
    protected PromiseStatusEntry() {
    }

    /**
     * Appends a timeline entry recording a promise's move to a status.
     *
     * @param promise     the owning promise (required; same-module FK).
     * @param status      the status moved to (required).
     * @param evidenceRef supporting evidence object-store reference, or {@code null}.
     * @param note        optional curator note, or {@code null}.
     * @return the populated, transient entity.
     */
    public static PromiseStatusEntry record(Promise promise, PromiseStatus status,
                                            String evidenceRef, String note) {
        PromiseStatusEntry e = new PromiseStatusEntry();
        e.promise = promise;
        e.status = status;
        e.evidenceRef = evidenceRef;
        e.note = note;
        return e;
    }

    /** @return the owning promise (same-module aggregate). */
    public Promise getPromise() {
        return promise;
    }

    /** @return the status the promise moved to at this entry. */
    public PromiseStatus getStatus() {
        return status;
    }

    /** @return the supporting evidence object-store reference, or {@code null}. */
    public String getEvidenceRef() {
        return evidenceRef;
    }

    /** @return the optional curator note, or {@code null}. */
    public String getNote() {
        return note;
    }
}
