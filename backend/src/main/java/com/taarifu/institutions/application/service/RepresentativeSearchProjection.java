package com.taarifu.institutions.application.service;

import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.institutions.domain.model.Representative;
import com.taarifu.institutions.domain.model.enums.RepresentativeStatus;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;

import java.util.UUID;

/**
 * The single, shared builder of a {@link Representative}'s <b>public discovery projection</b> for the
 * cross-entity search index (ADR-0017 §1, §4). It is the one source of truth for "what does a representative
 * look like in search, and at what visibility" — reused by both index-population paths so the projection and
 * its privacy fence can never drift apart (DRY, CLAUDE.md §3):
 * <ul>
 *   <li>the <b>live write path</b> — {@code InstitutionsAdminService} on create/update; and</li>
 *   <li>the <b>backfill path</b> — {@code RepresentativeSearchBackfillSource} re-pushing pre-existing rows
 *       through the same {@code search.api.SearchIndexApi}.</li>
 * </ul>
 *
 * <h3>What is indexed — and why no PII (PRD §18, ADR-0017 §1)</h3>
 * <p>Only the <b>public civic identity</b>: the seat label as the title (kind + seat name, e.g.
 * "Mbunge — Rombo" / "Diwani — Mengwe (Kata)"), Swahili+English role/mandate/legislature keyword facets, and
 * the area public id (constituency or ward) for the area filter. The representative's {@code bio} (moderated
 * free text) and any linked {@code profileId} (the citizen's account) are <b>deliberately NOT pushed</b> — the
 * index is a lean discovery surface, not a profile mirror; {@code authoredByAccountId} is left {@code null}
 * because a representative is a directory entity, not an authored post (no author-suspension visibility
 * maintenance applies — ADR-0017 §3).</p>
 *
 * <h3>Visibility (ADR-0017 §4) — the load-bearing fence</h3>
 * <p>A {@link RepresentativeStatus#SITTING} or {@link RepresentativeStatus#FORMER} representative is public
 * civic data (the directory + historical record are public — PRD §22.6), so {@link SearchVisibility#PUBLIC}. A
 * {@link RepresentativeStatus#PENDING_VERIFICATION} record is not yet on the official list and must not surface
 * to the public (anti-claim-spoofing, UC-A22/D-Q2), so it is indexed {@link SearchVisibility#STAFF} —
 * discoverable by staff, invisible to guests/citizens until verified. Centralising this here is exactly why the
 * backfill is correct: it indexes every live row (PENDING → STAFF, SITTING/FORMER → PUBLIC) with the same
 * visibility the live path would set, rather than re-deriving (and risking drifting) the fence.</p>
 *
 * <p>Stateless and side-effect-free: it builds a value object and touches no repository or index. The caller
 * owns the transaction within which the representative's {@code constituency}/{@code ward} LAZY associations are
 * read here for the title/area facet.</p>
 */
public final class RepresentativeSearchProjection {

    private RepresentativeSearchProjection() {
        // Pure static helper — never instantiated.
    }

    /**
     * Builds the representative's public {@link SearchDocumentUpsert} projection (idempotent upsert key
     * {@code (REPRESENTATIVE, publicId)}). Carries public-display data + opaque ids only — never PII.
     *
     * @param rep the persisted representative (its {@code constituency}/{@code ward} associations are read here
     *            for the title/area facet; both may be {@code null} for special-seats/nominated).
     * @return the public projection to push through {@code search.api.SearchIndexApi.upsert}.
     */
    public static SearchDocumentUpsert of(Representative rep) {
        return new SearchDocumentUpsert(
                SearchEntityType.REPRESENTATIVE,
                rep.getPublicId(),
                title(rep),
                null,                 // snippetSw: the seat label is in the title; no extra public snippet
                null,                 // snippetEn
                keywords(rep),        // SW+EN facet terms (kind/mandate/legislature) — public, no PII
                areaId(rep),          // constituency or ward public id for the area filter
                null,                 // categoryId: a representative is not category-scoped
                visibility(rep),
                null);                // authoredByAccountId: a directory entity has no author (never the rep's account)
    }

    /**
     * Resolves the Ward-or-coarser area public id for a representative's area facet (ADR-0017 §4): the
     * constituency (Jimbo) for a constituency-mandate MP, the ward (Kata) for a councillor/ward-exec, or
     * {@code null} for a seat-less (special-seats/nominated) member. A bare public id, never a FK
     * (cross-module reference by id — ADR-0013).
     */
    private static UUID areaId(Representative rep) {
        if (rep.getConstituency() != null) {
            return rep.getConstituency().getPublicId();
        }
        if (rep.getWard() != null) {
            return rep.getWard().getPublicId();
        }
        return null;
    }

    /**
     * Builds the public display title for a representative document: the seat kind plus the seat name (Jimbo or
     * Kata), e.g. {@code "Mbunge — Rombo"}, {@code "Diwani — Mengwe (Kata)"}, or just {@code "Mbunge"} for a
     * seat-less (special-seats/nominated) member. Swahili-first (PRD §14): the civic role term is the Swahili
     * one citizens search by. Carries no PII.
     */
    private static String title(Representative rep) {
        String role = switch (rep.getType()) {
            case MP -> "Mbunge";
            case COUNCILLOR -> "Diwani";
            case WARD_EXEC -> "Mtendaji wa Kata";
        };
        Constituency constituency = rep.getConstituency();
        if (constituency != null) {
            return role + " — " + constituency.getName();
        }
        Location ward = rep.getWard();
        if (ward != null) {
            return role + " — " + ward.getName() + " (Kata)";
        }
        return role;
    }

    /**
     * Builds the public keyword facets for a representative document — Swahili and English role synonyms plus
     * the mandate/legislature names — so a citizen typing {@code mbunge}, {@code mp}, {@code diwani},
     * {@code councillor}, or a Viti Maalum term finds the right rows even though the {@code simple} FTS config
     * does not stem Swahili (ADR-0017 §2). Enum names only; no PII.
     */
    private static String keywords(Representative rep) {
        StringBuilder kw = new StringBuilder();
        switch (rep.getType()) {
            case MP -> kw.append("mbunge mp bunge");
            case COUNCILLOR -> kw.append("diwani councillor halmashauri");
            case WARD_EXEC -> kw.append("mtendaji ward executive kata");
        }
        kw.append(' ').append(rep.getMandate().name()).append(' ').append(rep.getLegislature().name());
        return kw.toString();
    }

    /**
     * Maps a representative's lifecycle status to its discovery visibility (ADR-0017 §4): SITTING/FORMER are
     * public civic data ({@link SearchVisibility#PUBLIC}); a PENDING_VERIFICATION claim is staff-only
     * ({@link SearchVisibility#STAFF}) until it is verified against the official list (UC-A22).
     */
    private static SearchVisibility visibility(Representative rep) {
        return rep.getStatus() == RepresentativeStatus.PENDING_VERIFICATION
                ? SearchVisibility.STAFF
                : SearchVisibility.PUBLIC;
    }
}
