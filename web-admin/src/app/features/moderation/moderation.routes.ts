import { Routes } from '@angular/router';

/**
 * Lazy-loaded moderation routes (PRD §18, §25.8). Flag queue + appeals, each lazily loaded.
 *
 * <p>NOTE: every endpoint these screens call is `hasRole('MODERATOR')`-gated on the SERVER, and the
 * conflict-of-interest / appeal-independence guards (D16, §25.8) are enforced in the backend service
 * regardless of client routing (ARCHITECTURE.md §6.2).</p>
 */
export const MODERATION_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./moderation-queue.component').then((m) => m.ModerationQueueComponent),
    title: 'Taarifu Admin — Moderation',
  },
  {
    path: 'appeals',
    loadComponent: () => import('./appeals.component').then((m) => m.AppealsComponent),
    title: 'Taarifu Admin — Appeals',
  },
];
