import { Routes } from '@angular/router';

/**
 * Lazy-loaded Users & Roles admin routes (M14, US-14.1, UC-H06; PRD §6.4, §7.1). A filterable account
 * directory ({@code GET /admin/users}) and a per-account detail page with role grant/revoke + suspend/
 * reinstate ({@code /admin/users/{id}/...}). The `:userId` segment is bound to the detail component's
 * `userId` input via the router's component-input-binding (configured in app.config.ts).
 *
 * <p>NOTE: every management endpoint these screens call is `hasAnyRole('ADMIN','ROOT')`-gated on the SERVER
 * (the mutations additionally enforce the no-self-action fence, D16), regardless of client routing
 * (ARCHITECTURE.md §6.2).</p>
 */
export const USERS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./users-list.component').then((m) => m.UsersListComponent),
    title: 'Taarifu Admin — Users & Roles',
  },
  {
    path: ':userId',
    loadComponent: () => import('./user-detail.component').then((m) => m.UserDetailComponent),
    title: 'Taarifu Admin — Account',
  },
];
