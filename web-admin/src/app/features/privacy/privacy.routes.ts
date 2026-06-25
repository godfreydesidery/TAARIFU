import { Routes } from '@angular/router';

/**
 * Lazy-loaded Privacy / DSR admin routes — PDPA data-subject access & erasure handling (UC-A17 / UC-S09;
 * PRD §25.1, §18). A filterable queue of data-subject requests with inline acknowledge/complete/hold.
 *
 * <p>NOTE: every endpoint these screens call is ADMIN/ROOT-gated on the SERVER (PRD App. R: "Trigger
 * data-export/erasure job" = ✅ Admin/Root) and lives under {@code /privacy/dsr}, regardless of client
 * routing. The screen degrades gracefully if those endpoints are not deployed in this environment (§15).</p>
 */
export const PRIVACY_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./dsr-list.component').then((m) => m.DsrListComponent),
    title: 'Taarifu Admin — Privacy & DSR',
  },
];
