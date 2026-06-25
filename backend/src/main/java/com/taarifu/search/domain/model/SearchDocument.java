package com.taarifu.search.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * One denormalised, full-text-indexed <b>read-projection</b> of a searchable entity owned by another module
 * (ADR-0017 §2). This is the search index row — not a source of truth.
 *
 * <p>Responsibility: hold the small, public, searchable slice of a representative / organisation / announcement
 * / issue-category / public-report so {@code GET /search} can match by keyword and filter by area/category in a
 * single indexed query, then hand the client the source aggregate's {@link #entityPublicId} to re-read the full
 * record from its owning module. The authoritative data stays in the owner; this table is a derived projection
 * the owner <b>pushes</b> via {@link com.taarifu.search.api.SearchIndexApi} (owner→search direction, no reach-in
 * — ADR-0013 §1). The search module never reads any sibling's {@code domain}/{@code infrastructure}.</p>
 *
 * <p><b>🔒 Public-display data + opaque ids only — never PII</b> (PRD §18, ADR-0017 §1): the projection carries
 * a display {@link #title}, public localised snippets, free {@link #keywords}, and opaque {@link #areaId}/
 * {@link #categoryId}/{@link #entityPublicId} UUIDs. It must never carry a phone, national/voter ID, free GPS
 * point, or any private body text. {@link #authoredByAccountId} is an opaque account UUID used <i>only</i> for
 * visibility maintenance (hiding a suspended author's rows — §3) and is never returned to a reader.</p>
 *
 * <p><b>Cross-module references are bare UUIDs, never FKs</b> (ADR-0013 §3.2, ARCHITECTURE §3.2): the source
 * aggregate, its area, its category, and its author all live in other modules; referencing them by public id
 * (not a foreign key) keeps the boundary closed and lets search become a standalone service later (ARCHITECTURE
 * §10).</p>
 *
 * <p><b>The FTS column is NOT mapped here.</b> The migration (V146) declares {@code search_vector} as a Postgres
 * {@code tsvector GENERATED ALWAYS … STORED} column derived from {@link #title}/{@link #keywords}/the snippets.
 * Mapping a {@code tsvector} onto a JPA field is fragile and unnecessary — the column is written by the database,
 * read only by the native FTS query, and {@code ddl-auto=validate} tolerates extra (unmapped) columns. Keeping
 * it off the entity means the generated column can never drift from, or be accidentally overwritten by, JPA
 * (ADR-0017 §2).</p>
 *
 * <p>WHY no Lombok / explicit mutators: the row is a managed projection; the package-private factory
 * {@link #project} and {@link #applyUpdate}/{@link #hide} are the only mutation surface so an upsert always lands
 * a consistent row (CLAUDE.md §8 — Lombok sparingly).</p>
 */
@Entity
@SQLRestriction("deleted = false")
@Table(name = "search_document", indexes = {
        @Index(name = "ix_search_document_type", columnList = "entity_type"),
        @Index(name = "ix_search_document_area", columnList = "area_id"),
        @Index(name = "ix_search_document_category", columnList = "category_id"),
        @Index(name = "ix_search_document_author", columnList = "authored_by_account_id")
        // The GIN index on the generated search_vector column and the live-scoped unique
        // (entity_type, entity_public_id) are partial/expression indexes JPA cannot express — they
        // are declared in V146 only (ADR-0017 §2).
})
public class SearchDocument extends BaseEntity {

    /** Which kind of entity this row projects (drives result grouping + the {@code type} filter). */
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private SearchEntityType entityType;

    /** The source aggregate's <b>public</b> id (opaque cross-module ref — never a FK). The client re-reads by it. */
    @Column(name = "entity_public_id", nullable = false)
    private UUID entityPublicId;

    /** Primary display label (rep name+seat / org name / announcement·category·report title). */
    @Column(name = "title", nullable = false, length = 512)
    private String title;

    /** Short localised (Swahili) public preview; {@code null} when the owner supplies no SW snippet. */
    @Column(name = "snippet_sw", length = 1024)
    private String snippetSw;

    /** Short localised (English) public preview; {@code null} when the owner supplies no EN snippet. */
    @Column(name = "snippet_en", length = 1024)
    private String snippetEn;

    /** Extra searchable terms the owner supplies (Swahili synonyms, codes); {@code null} if none. */
    @Column(name = "keywords", length = 1024)
    private String keywords;

    /** Ward-or-coarser area public id for the area filter (PRD §9.0); bare UUID, may be {@code null}. */
    @Column(name = "area_id")
    private UUID areaId;

    /** Issue-category public id for the category filter; bare UUID, may be {@code null}. */
    @Column(name = "category_id")
    private UUID categoryId;

    /** Visibility tier — the server-side gate ({@code PUBLIC} for any reader, {@code STAFF} for staff only). */
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16)
    private SearchVisibility visibility;

    /** Opaque author account public id — used ONLY for visibility maintenance (§3); never returned. May be {@code null}. */
    @Column(name = "authored_by_account_id")
    private UUID authoredByAccountId;

    /** JPA no-arg constructor (required by Hibernate). */
    protected SearchDocument() {
    }

    /**
     * Builds a fresh projection row from an owner's pushed data (ADR-0017 §1).
     *
     * @param entityType          the kind of entity (never {@code null}).
     * @param entityPublicId      the source aggregate's public id (never {@code null}).
     * @param title               the display label (never {@code null}/blank).
     * @param snippetSw           localised SW snippet, or {@code null}.
     * @param snippetEn           localised EN snippet, or {@code null}.
     * @param keywords            extra search terms, or {@code null}.
     * @param areaId              area public id, or {@code null}.
     * @param categoryId          category public id, or {@code null}.
     * @param visibility          the visibility tier (never {@code null}).
     * @param authoredByAccountId author account public id (for visibility maintenance), or {@code null}.
     * @return the new, unsaved projection row.
     */
    public static SearchDocument project(SearchEntityType entityType, UUID entityPublicId, String title,
                                         String snippetSw, String snippetEn, String keywords,
                                         UUID areaId, UUID categoryId, SearchVisibility visibility,
                                         UUID authoredByAccountId) {
        SearchDocument doc = new SearchDocument();
        doc.entityType = entityType;
        doc.entityPublicId = entityPublicId;
        doc.applyUpdate(title, snippetSw, snippetEn, keywords, areaId, categoryId, visibility, authoredByAccountId);
        return doc;
    }

    /**
     * Overwrites the mutable projection fields on an existing row — the update half of an idempotent upsert
     * (a re-push of the same {@code (entity_type, entity_public_id)} updates one row, never duplicates).
     * {@code entity_type}/{@code entity_public_id} are immutable identity and are not touched.
     *
     * @param title               the display label (never {@code null}/blank).
     * @param snippetSw           localised SW snippet, or {@code null}.
     * @param snippetEn           localised EN snippet, or {@code null}.
     * @param keywords            extra search terms, or {@code null}.
     * @param areaId              area public id, or {@code null}.
     * @param categoryId          category public id, or {@code null}.
     * @param visibility          the visibility tier (never {@code null}).
     * @param authoredByAccountId author account public id, or {@code null}.
     */
    public void applyUpdate(String title, String snippetSw, String snippetEn, String keywords,
                            UUID areaId, UUID categoryId, SearchVisibility visibility,
                            UUID authoredByAccountId) {
        this.title = title;
        this.snippetSw = snippetSw;
        this.snippetEn = snippetEn;
        this.keywords = keywords;
        this.areaId = areaId;
        this.categoryId = categoryId;
        this.visibility = visibility;
        this.authoredByAccountId = authoredByAccountId;
    }

    /**
     * Drops this row out of public discovery by forcing it to {@link SearchVisibility#STAFF} — the effect of
     * the moderation visibility-maintenance handler when its author is suspended (§3). Idempotent: hiding an
     * already-hidden row is a no-op.
     */
    public void hide() {
        this.visibility = SearchVisibility.STAFF;
    }

    /** @return the kind of entity projected. */
    public SearchEntityType getEntityType() {
        return entityType;
    }

    /** @return the source aggregate's public id (the client re-reads the full record by this). */
    public UUID getEntityPublicId() {
        return entityPublicId;
    }

    /** @return the display label. */
    public String getTitle() {
        return title;
    }

    /** @return the localised SW snippet, or {@code null}. */
    public String getSnippetSw() {
        return snippetSw;
    }

    /** @return the localised EN snippet, or {@code null}. */
    public String getSnippetEn() {
        return snippetEn;
    }

    /** @return the extra search keywords, or {@code null}. */
    public String getKeywords() {
        return keywords;
    }

    /** @return the area public id, or {@code null}. */
    public UUID getAreaId() {
        return areaId;
    }

    /** @return the category public id, or {@code null}. */
    public UUID getCategoryId() {
        return categoryId;
    }

    /** @return the visibility tier. */
    public SearchVisibility getVisibility() {
        return visibility;
    }

    /** @return the author account public id (internal use only), or {@code null}. */
    public UUID getAuthoredByAccountId() {
        return authoredByAccountId;
    }
}
