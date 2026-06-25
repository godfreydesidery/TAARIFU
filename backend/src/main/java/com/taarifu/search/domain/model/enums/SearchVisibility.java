package com.taarifu.search.domain.model.enums;

/**
 * The visibility tier of a {@link com.taarifu.search.domain.model.SearchDocument} — the server-side gate that
 * decides who a search result is shown to (ADR-0017 §4; PRD §18).
 *
 * <p>Responsibility: encode "may any reader see this in discovery, or only authorised staff?". It is the single
 * predicate the query service applies so private/sensitive rows are <b>filtered out of the result set</b> for a
 * guest or ordinary citizen — never returned and never individually 403'd (which would be an enumeration vector,
 * PRD §18). The owning module sets this on push: public civic data ({@code PUBLISHED} announcement, public
 * report, the directory) is {@code PUBLIC}; anything private/sensitive/in-flight is {@code STAFF}.</p>
 *
 * <p>WHY only two tiers (not a full ACL): the discovery surface is coarse — "public civic graph" vs "staff-only".
 * Fine-grained scope (a responder's assigned areas/categories) is enforced where the full record is read in the
 * owning module; the index just keeps non-public rows out of the public result set (KISS). A future
 * scope-aware tier is a documented follow-up (ADR-0017 revisit).</p>
 */
public enum SearchVisibility {

    /** Visible to any reader, including unauthenticated guests — the public civic graph. */
    PUBLIC,

    /** Visible only to authenticated staff (MODERATOR/ADMIN/ROOT); filtered out for guests/citizens. */
    STAFF
}
