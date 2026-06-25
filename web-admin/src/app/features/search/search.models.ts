/**
 * Global-search DTOs — mirror the backend `SearchPort` results served under `GET /search/**`
 * (EI-10 / SY5; PRD §21 EI-10, §16). The console's topbar search box and results page bind to these.
 *
 * <p>WHY these shapes: the search index spans only PUBLIC, indexable entities — representatives, responder
 * organisations, announcements, and PUBLIC reports (PRD EI-10: "reps, projects, announcements, public
 * reports"). Each hit therefore carries just enough to render a labelled, deep-linkable row: a stable
 * {@link type} discriminator the client branches on (never a localised label), the indexed entity's public
 * id, a title, and an optional snippet/subtitle. Sensitive/private content is never indexed server-side, so
 * the console can render every hit without a visibility check (PRD §20, SR-5: sensitive reports are forced
 * PRIVATE and "never public/searchable"). Degradation: if the external engine is down the server falls back
 * to Postgres FTS / direct DB; the client contract is identical across both (PRD §21 EI-10).</p>
 */

/**
 * The entity kinds the global search can return. STABLE tokens — the client maps each to an icon, a
 * grouped section, and a deep link; it must branch on these, never on the localised label.
 */
export const SEARCH_RESULT_TYPES = ['REPORT', 'REPRESENTATIVE', 'ORGANISATION', 'ANNOUNCEMENT'] as const;

/** One global-search result type token (see {@link SEARCH_RESULT_TYPES}). */
export type SearchResultType = (typeof SEARCH_RESULT_TYPES)[number];

/**
 * A single search hit (mirrors the backend `SearchHitDto`). PII-minimised: a public report hit carries its
 * code/title only — never a reporter, precise geo, or body beyond the indexed snippet (PRD §18).
 */
export interface SearchHit {
  /** Stable entity-kind discriminator (drives the icon, group, and deep link). */
  type: SearchResultType;
  /** The matched entity's public id (UUID/ULID) — the deep-link target. */
  id: string;
  /** Primary label (report title, representative/org name, announcement title). */
  title: string;
  /**
   * Optional secondary line — a report ticket code, a representative's constituency, an org type, or an
   * announcement window — already safe to show (no PII). `null` when the server has nothing to add.
   */
  subtitle: string | null;
  /** Optional matched-text snippet from the index (may contain `<mark>`-free plain text), or `null`. */
  snippet: string | null;
}

/**
 * The full search response (mirrors the backend `SearchResultsDto`). The server returns a flat, ranked hit
 * list plus the echoed query and total; the client groups by {@link SearchHit.type} for display so the
 * grouping stays a presentation concern (and survives a future ranking change).
 */
export interface SearchResults {
  /** The query string the server actually ran (echoed for display/confirmation). */
  query: string;
  /** Total hits matched across all types (may exceed the returned `hits.length` if capped). */
  total: number;
  /** The ranked hits (best first), across all entity types. */
  hits: SearchHit[];
}
