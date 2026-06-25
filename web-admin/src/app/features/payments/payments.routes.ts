import { Routes } from '@angular/router';

/**
 * Lazy-loaded mobile-money payments admin routes — Phase-2 token-purchase reconciliation (D19; PRD §23,
 * §21 EI-20). A filterable payments ledger with an aggregate totals strip; payer MSISDN shown masked.
 *
 * <p>NOTE: the payments admin query is ADMIN/ROOT-gated on the SERVER, regardless of client routing. The
 * screen degrades gracefully if the payments endpoints are not deployed in this environment (PRD §15) — the
 * totals strip hides and the list shows an error/retry. Tokens never buy democratic weight (§23).</p>
 */
export const PAYMENTS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./payments-list.component').then((m) => m.PaymentsListComponent),
    title: 'Taarifu Admin — Payments',
  },
];
