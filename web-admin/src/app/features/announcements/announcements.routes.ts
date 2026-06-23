import { Routes } from '@angular/router';

/**
 * Lazy-loaded announcements routes (PRD §12, §22.6, M4).
 *
 * <p>Two routes: the landing lookup (by-id entry + API-gap note, since no console-wide list endpoint
 * exists yet) and the detail view. The {@code :announcementId} segment is bound to the detail component's
 * {@code announcementId} input via the router's component-input-binding (configured in app.config.ts).</p>
 *
 * <p>NOTE: the announcement detail read is PUBLIC server-side ({@code GET /announcements/{id}},
 * {@code permitAll()}); the console still routes it behind the authenticated shell as a staff convenience —
 * the SERVER, not client routing, is the real gate (ARCHITECTURE.md §6.2).</p>
 */
export const ANNOUNCEMENTS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./announcements-lookup.component').then((m) => m.AnnouncementsLookupComponent),
    title: 'Taarifu Admin — Announcements',
  },
  {
    path: ':announcementId',
    loadComponent: () =>
      import('./announcement-detail.component').then((m) => m.AnnouncementDetailComponent),
    title: 'Taarifu Admin — Announcement',
  },
];
