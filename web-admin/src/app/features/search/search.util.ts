import { SearchHit, SearchResultType } from './search.models';

/**
 * Pure mapping helpers shared by the topbar search box and the full results page — ONE place that decides
 * how a {@link SearchResultType} maps to its deep link, icon, group heading, and group order (DRY,
 * CLAUDE.md §3). Centralising this stops the dropdown and the page from drifting on how a hit links/labels.
 */

/** The stable display order of result groups + their heading i18n keys (civic-sensible: people, then ops). */
export const SEARCH_GROUP_ORDER: ReadonlyArray<{ type: SearchResultType; labelKey: string }> = [
  { type: 'REPRESENTATIVE', labelKey: 'search.groupRepresentatives' },
  { type: 'ORGANISATION', labelKey: 'search.groupOrganisations' },
  { type: 'REPORT', labelKey: 'search.groupReports' },
  { type: 'ANNOUNCEMENT', labelKey: 'search.groupAnnouncements' },
];

/** A decorative glyph per entity type (icon+label for scannability + low-literacy accessibility). */
export function iconFor(type: SearchResultType): string {
  switch (type) {
    case 'REPRESENTATIVE':
      return '♟';
    case 'ORGANISATION':
      return '⚙';
    case 'REPORT':
      return '✉';
    case 'ANNOUNCEMENT':
      return '📢';
    default:
      return '•';
  }
}

/**
 * The router-link array a hit navigates to, deep-linking into the owning feature.
 *
 * <p>WHY a function and not a field on the hit: the deep link is a CLIENT routing concern (the server only
 * knows the entity id), and not every type has a per-id detail route — representatives are a flat list, so a
 * representative hit links to the directory rather than a non-existent detail page. Keeping this here means
 * the routing map lives in one place the topbar + results page both read.</p>
 *
 * @param hit the search hit.
 * @returns the `routerLink` array.
 */
export function deepLinkFor(hit: SearchHit): unknown[] {
  switch (hit.type) {
    case 'REPORT':
      // Owner-grade case detail (ADMIN/MODERATOR-gated server-side).
      return ['/reports', hit.id];
    case 'ORGANISATION':
      return ['/responders', hit.id];
    case 'ANNOUNCEMENT':
      return ['/announcements', hit.id];
    case 'REPRESENTATIVE':
      // Representatives have no per-id detail route yet (flat directory) — link to the list.
      return ['/representatives'];
    default:
      return ['/dashboard'];
  }
}
