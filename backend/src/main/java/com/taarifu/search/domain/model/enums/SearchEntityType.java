package com.taarifu.search.domain.model.enums;

/**
 * The kinds of entity the cross-entity discovery index covers (ADR-0017 §2; PRD discovery).
 *
 * <p>Responsibility: the closed vocabulary stamped on every {@link com.taarifu.search.domain.model.SearchDocument}
 * row and accepted as the optional {@code type} filter on {@code GET /search}. Each value names a kind of thing
 * a citizen or staff member discovers — a representative, an organisation/responder, an announcement, an issue
 * category, or a public report — and tells the client which owning module to re-read the full record from by the
 * document's {@code entity_public_id} (the search index never returns the full aggregate — ADR-0013).</p>
 *
 * <p>WHY an enum (not free strings): it makes the indexable surface reviewable in one place, lets the result DTO
 * carry a typed discriminator the client groups on, and is the natural {@code type} filter domain. Values are
 * append-only — adding a new searchable entity adds a value here; never repurpose an existing one (clients and
 * stored rows depend on the literal name).</p>
 */
public enum SearchEntityType {

    /** An elected representative — MP (Mbunge) or Councillor (Diwani); owned by {@code institutions}. */
    REPRESENTATIVE,

    /** A government/parastatal/private responder organisation in the directory; owned by {@code responders}. */
    ORGANISATION,

    /** A published civic announcement; owned by {@code communications}. */
    ANNOUNCEMENT,

    /** An issue category (e.g. Maji/Water, Barabara/Roads); owned by {@code reporting}. */
    ISSUE_CATEGORY,

    /** A publicly-visible issue report (ticket); owned by {@code reporting}. */
    PUBLIC_REPORT,

    /**
     * A publicly-visible petition (non-DRAFT — ACTIVE/SUCCEEDED/RESPONDED/CLOSED); owned by
     * {@code engagement}. Only its public title + lean summary + area/category facets are indexed — never
     * a DRAFT, never the signer list, never any PII (the discovery fence, ADR-0017 §1, PRD §18).
     */
    PETITION,

    /**
     * A publicly-visible survey or poll (non-DRAFT — SCHEDULED/OPEN/CLOSED/ARCHIVED); owned by
     * {@code engagement}. Covers BOTH the SURVEY and POLL {@code SurveyType}s (engagement models them as one
     * {@code Survey} aggregate), so one value serves both. Only its public title + description snippet +
     * area facet are indexed — never the questions JSON, never any response payload, never a DRAFT.
     */
    POLL,

    /**
     * A publicly-visible Q&amp;A question to a representative (OPEN/ANSWERED); owned by {@code engagement}.
     * Only its public body snippet + the target-rep facet are indexed — never the asker id, never a
     * DECLINED/MODERATED/hidden question.
     */
    QUESTION
}
