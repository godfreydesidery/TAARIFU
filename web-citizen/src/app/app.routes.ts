import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';

/**
 * Top-level route table for the citizen PWA.
 *
 * <p>Structure: a PUBLIC `/auth` route (phone + OTP), then the SHELL ({@link ShellComponent}) wrapping all
 * feature screens as lazily-loaded children. PUBLIC screens (feed, find-my-rep, search) are NOT guarded so
 * a guest — or an offline citizen reading SW-cached content — can browse them. Screens that act on the
 * citizen's identity (file a report, track my reports) are protected by {@link authGuard} (a client
 * convenience; the server is the real gate). Every screen is `loadComponent` lazy so the initial bundle
 * stays minimal for low-end devices on slow links (PRD §15).</p>
 */
export const routes: Routes = [
  {
    path: 'auth',
    loadComponent: () => import('./features/auth/auth.component').then((m) => m.AuthComponent),
    title: 'Taarifu — Ingia',
  },
  {
    path: '',
    loadComponent: () => import('./layout/shell/shell.component').then((m) => m.ShellComponent),
    children: [
      { path: '', redirectTo: 'feed', pathMatch: 'full' },

      // ----- Public (guest-readable, offline-cached) -----
      {
        path: 'feed',
        loadComponent: () => import('./features/feed/feed.component').then((m) => m.FeedComponent),
        title: 'Taarifu — Mlisho',
      },
      {
        path: 'feed/search',
        loadComponent: () => import('./features/feed/search.component').then((m) => m.SearchComponent),
        title: 'Taarifu — Tafuta',
      },
      {
        path: 'representatives',
        loadComponent: () =>
          import('./features/representatives/find-rep.component').then((m) => m.FindRepComponent),
        title: 'Taarifu — Wawakilishi',
      },

      // ----- Citizen-authenticated -----
      {
        path: 'report',
        loadComponent: () => import('./features/report/file-report.component').then((m) => m.FileReportComponent),
        canActivate: [authGuard],
        title: 'Taarifu — Ripoti',
      },
      {
        path: 'track',
        loadComponent: () => import('./features/track/track-list.component').then((m) => m.TrackListComponent),
        canActivate: [authGuard],
        title: 'Taarifu — Ripoti Zangu',
      },
      {
        path: 'track/:reportId',
        loadComponent: () => import('./features/track/track-detail.component').then((m) => m.TrackDetailComponent),
        canActivate: [authGuard],
        title: 'Taarifu — Ufuatiliaji',
      },
    ],
  },
  // Unknown paths → the public feed.
  { path: '**', redirectTo: '' },
];
