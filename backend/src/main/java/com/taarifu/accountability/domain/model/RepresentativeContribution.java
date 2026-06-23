package com.taarifu.accountability.domain.model;

import com.taarifu.accountability.domain.model.enums.ContributionType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A single recorded contribution by a representative — a speech, motion, bill, question, vote, or
 * committee activity (PRD §10 Epic M6, US-6.1; EI-11).
 *
 * <p>Responsibility: the read-mostly accountability record citizens browse to judge a representative's
 * work. <b>Curated authorship (D-Q4):</b> rows are created by an authorised author / {@code ROLE_ADMIN}
 * (curator tooling), <b>not</b> by citizens — Taarifu is not the source of truth, so every item carries
 * provenance via {@link #sourceUrl} and is shown with its origin (EI-11). Citizens read-only.</p>
 *
 * <p>WHY {@code representativeId} is an opaque {@link UUID}, not a {@code @ManyToOne} FK to a
 * representative entity: the representative aggregate lives in the <b>institutions</b> module, which this
 * module must not import (module-boundary isolation). It is referenced by public id and resolved through
 * institutions' public API at the wiring step (// TODO(wiring)). This is the deliberate cross-module
 * exception — a real FK would breach the boundary (ARCHITECTURE.md §4.3 spirit).</p>
 *
 * <p>WHY {@code attachmentRefs} are object-store <b>references</b>, not blobs: documents (Hansard PDFs,
 * order papers) live behind signed URLs in object storage; only keys are stored here (PRD §18, EI-8).</p>
 */
@Entity
@Table(name = "representative_contribution", indexes = {
        @Index(name = "ix_contribution_representative", columnList = "representative_id"),
        @Index(name = "ix_contribution_type", columnList = "type"),
        @Index(name = "ix_contribution_date", columnList = "occurred_on")
})
@SQLRestriction("deleted = false")
public class RepresentativeContribution extends BaseEntity {

    /**
     * Public id of the representative this contribution belongs to (institutions module — referenced by
     * id only, never an FK; // TODO(wiring): validate against institutions' public API).
     */
    @Column(name = "representative_id", nullable = false)
    private UUID representativeId;

    /** The contribution kind (SPEECH/MOTION/BILL/QUESTION/VOTE/COMMITTEE). */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private ContributionType type;

    /** Short human title of the contribution. */
    @Column(name = "title", nullable = false, length = 280)
    private String title;

    /** Longer summary / narrative of the contribution (curated). */
    @Column(name = "summary", length = 4000)
    private String summary;

    /** The date the contribution occurred (House sitting date). */
    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    /** Free-text parliamentary session/term reference (e.g. {@code "12th Parliament, 3rd Session"}). */
    @Column(name = "parliament_session", length = 120)
    private String parliamentSession;

    /** Provenance URL for the source record (Hansard/official) — accountability must be attributable. */
    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    /**
     * Object-store references to supporting documents (never the bytes). Stored in side table
     * {@code contribution_attachment} (PRD §18, EI-8).
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "contribution_attachment",
            joinColumns = @JoinColumn(name = "contribution_id"))
    @Column(name = "attachment_ref", nullable = false, length = 512)
    private List<String> attachmentRefs = new ArrayList<>();

    /** JPA requires a no-arg constructor; not for application use. */
    protected RepresentativeContribution() {
    }

    /**
     * Creates a curated contribution record.
     *
     * @param representativeId  public id of the subject representative (institutions module).
     * @param type              the contribution kind.
     * @param title             short title (required).
     * @param summary           longer summary, or {@code null}.
     * @param occurredOn        the sitting date (required).
     * @param parliamentSession session/term reference, or {@code null}.
     * @param sourceUrl         provenance URL, or {@code null}.
     * @param attachmentRefs    object-store references, or {@code null}/empty.
     * @return the populated, transient entity.
     */
    public static RepresentativeContribution create(UUID representativeId, ContributionType type,
                                                    String title, String summary, LocalDate occurredOn,
                                                    String parliamentSession, String sourceUrl,
                                                    List<String> attachmentRefs) {
        RepresentativeContribution c = new RepresentativeContribution();
        c.representativeId = representativeId;
        c.type = type;
        c.title = title;
        c.summary = summary;
        c.occurredOn = occurredOn;
        c.parliamentSession = parliamentSession;
        c.sourceUrl = sourceUrl;
        if (attachmentRefs != null) {
            c.attachmentRefs.addAll(attachmentRefs);
        }
        return c;
    }

    /** @return the subject representative's public id (institutions module). */
    public UUID getRepresentativeId() {
        return representativeId;
    }

    /** @return the contribution kind. */
    public ContributionType getType() {
        return type;
    }

    /** @return the short title. */
    public String getTitle() {
        return title;
    }

    /** @return the longer summary, or {@code null}. */
    public String getSummary() {
        return summary;
    }

    /** @return the sitting date the contribution occurred. */
    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    /** @return the parliamentary session/term reference, or {@code null}. */
    public String getParliamentSession() {
        return parliamentSession;
    }

    /** @return the provenance URL, or {@code null}. */
    public String getSourceUrl() {
        return sourceUrl;
    }

    /** @return an unmodifiable view of the attachment object-store references. */
    public List<String> getAttachmentRefs() {
        return List.copyOf(attachmentRefs);
    }
}
