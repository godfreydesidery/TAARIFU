import { Routes } from '@angular/router';

/**
 * Lazy-loaded Users & Roles admin routes (PRD §6.4, §7). A single overview screen today — the generic
 * user directory + role-grant/scope endpoints do not yet exist server-side (a CENTRAL NEED), so this area
 * surfaces the operator's identity/roles + the role catalogue + the existing Representative-link grant
 * flow, and grows a real list/grant page once the backend exposes `GET /admin/users` +
 * `POST /admin/users/{id}/roles`.
 */
export const USERS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./users-list.component').then((m) => m.UsersListComponent),
    title: 'Taarifu Admin — Users & Roles',
  },
];
