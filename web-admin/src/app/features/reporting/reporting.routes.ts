import { Routes } from '@angular/router';

/**
 * Lazy-loaded reporting & case-management routes (PRD Epic M3). Queue list + case detail, each lazily
 * loaded. The `:reportId` segment is bound to the detail component's `reportId` input via the router's
 * component-input-binding (configured in app.config.ts).
 *
 * <p>NOTE: the queue is reachable only via the staff-gated nav; the SERVER enforces role on every status
 * action (ADMIN/MODERATOR/RESPONDER_*) regardless of client routing (ARCHITECTURE.md §6.2).</p>
 */
export const REPORTING_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./reports-list.component').then((m) => m.ReportsListComponent),
    title: 'Taarifu Admin — Reports',
  },
  {
    path: ':reportId',
    loadComponent: () => import('./report-detail.component').then((m) => m.ReportDetailComponent),
    title: 'Taarifu Admin — Report Case',
  },
];
