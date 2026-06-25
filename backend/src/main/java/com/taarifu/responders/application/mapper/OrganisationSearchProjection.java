package com.taarifu.responders.application.mapper;

import com.taarifu.responders.domain.model.Organisation;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import org.springframework.stereotype.Component;

/**
 * The <b>single source of truth</b> for an {@link Organisation}'s search-index projection + visibility
 * (ADR-0017 §1/§4, PRD §24.4). It builds the public {@link SearchDocumentUpsert} the responders module
 * pushes into the cross-entity discovery index for one organisation.
 *
 * <p><b>WHY a dedicated component (DRY — the fence cannot drift):</b> two code paths must produce the
 * <i>identical</i> projection and the <i>identical</i> visibility decision: (1) the live index-on-write
 * path ({@code ResponderAdminService} on create/update/verify) and (2) the one-off backfill
 * ({@code OrganisationSearchBackfillSource}). The {@code SearchBackfillSource} contract is explicit that
 * a backfill adapter MUST reuse the live producer's projection/visibility logic — "ideally the very same
 * private method" — so the privacy fence can never diverge between the two paths. Centralising it here is
 * exactly that: both callers delegate to {@link #project(Organisation)}, so a change to what is indexed
 * (or to the PUBLIC/STAFF rule) changes both at once (CLAUDE.md §3 DRY).</p>
 *
 * <h3>What is indexed — and why no PII (PRD §18, ADR-0017 §1)</h3>
 * <p>Only the <b>public directory identity</b>: the organisation name as the title and the organisation
 * type (e.g. {@code PARASTATAL}/{@code GOVERNMENT_AGENCY}) as an English keyword facet. The public
 * contact phone/email/URL are directory-display fields fetched from the full record on tap (the index
 * stays lean — PRD §15) and are deliberately NOT pushed. {@code authoredByAccountId} is {@code null}
 * because an organisation is a directory entity, not an authored post (no author-suspension visibility
 * maintenance applies — ADR-0017 §3). No citizen PII is ever involved — an organisation's contacts are
 * the body's own public details, not a citizen's.</p>
 *
 * <h3>Visibility (ADR-0017 §4) — mirror {@code isPubliclyListable}</h3>
 * <p>An organisation is indexed {@link SearchVisibility#PUBLIC} only when it is
 * {@link Organisation#isPubliclyListable()} (ACTIVE <b>and</b> verified — §24.4), exactly the rule the
 * public directory ({@code ResponderDirectoryService}) enforces; otherwise {@link SearchVisibility#STAFF}.
 * So a PENDING/unverified/suspended org is discoverable by staff but never surfaces to a guest/citizen
 * (the same anti-spoofing/anti-enumeration guarantee as the directory). The row is never deleted on
 * un-verify — an idempotent visibility flip keeps it consistent and re-listable on re-verification.</p>
 */
@Component
public class OrganisationSearchProjection {

    /**
     * Builds the public, PII-free search projection for one organisation, with visibility mirroring
     * {@link Organisation#isPubliclyListable()} (PUBLIC when ACTIVE+verified, otherwise STAFF). Idempotent
     * by construction: the projection is keyed on {@code (ORGANISATION, publicId)} downstream, so a re-push
     * updates the single live row.
     *
     * @param org the persisted/managed organisation (never {@code null}).
     * @return the {@link SearchDocumentUpsert} to push through {@code SearchIndexApi.upsert}.
     */
    public SearchDocumentUpsert project(Organisation org) {
        return new SearchDocumentUpsert(
                SearchEntityType.ORGANISATION,
                org.getPublicId(),
                org.getName(),
                null,                       // snippetSw: the name carries the label; no extra public snippet
                null,                       // snippetEn
                org.getType().name(),       // keyword facet: the organisation kind (enum name) — public, no PII
                null,                       // areaId: an org is not pinned to a single ward (responders carry coverage)
                null,                       // categoryId: capabilities (categories) live on Responder, not the org
                org.isPubliclyListable() ? SearchVisibility.PUBLIC : SearchVisibility.STAFF,
                null);                      // authoredByAccountId: a directory entity has no author
    }
}
