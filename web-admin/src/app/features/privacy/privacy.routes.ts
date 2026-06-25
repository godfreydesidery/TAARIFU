import { Routes } from '@angular/router';

/**
 * Lazy-loaded Privacy / DSR admin routes — PDPA data-subject access & erasure handling (UC-A17 / UC-S09;
 * PRD §25.1, §18). A filterable queue of data-subject requests with inline acknowledge/complete/reject.
 *
 * <p>NOTE: every endpoint these screens call is ADMIN/ROOT-gated on the SERVER (PRD App. R: "Trigger
 * data-export/erasure job" = ✅ Admin/Root), regardless of client routing. The screen degrades gracefully
 * if the privacy/admin endpoints are not deployed in this environment (PRD §15).</p>
 */
export const PRIVACY_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./dsr-list.component').then((m) => m.DsrListComponent),
    title: 'Taarifu Admin — Privacy & DSR',
  },
];
