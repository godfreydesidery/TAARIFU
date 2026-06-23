import { Routes } from '@angular/router';

/**
 * Lazy-loaded geography feature routes (PRD §9.0). Establishes the lazy `loadChildren` pattern: this
 * file (and its components) are only fetched when the operator first opens a geography page, keeping the
 * initial bundle small for low-end devices / slow links (PRD §15).
 */
export const GEOGRAPHY_ROUTES: Routes = [
  { path: '', redirectTo: 'regions', pathMatch: 'full' },
  {
    path: 'regions',
    loadComponent: () => import('./regions-list.component').then((m) => m.RegionsListComponent),
    title: 'Taarifu Admin — Geography',
  },
];
