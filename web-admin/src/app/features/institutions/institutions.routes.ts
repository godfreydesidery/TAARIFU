import { Routes } from '@angular/router';

/**
 * Lazy-loaded institutions admin routes (PRD §9.1; UC-B12/B13, UC-C04). Parliament terms CRUD,
 * parliament-roles CRUD, and the representative create/link form — each lazily loaded. The `:parliamentId`
 * and `:roleId` segments are bound to the form component inputs via component-input-binding.
 *
 * <p>NOTE: every write is `hasRole('ADMIN')`-gated on the SERVER; the representative link enforces the
 * additive-role + mandate⇄geography invariants in the backend service (§6.4 D12).</p>
 */
export const INSTITUTIONS_ROUTES: Routes = [
  { path: '', redirectTo: 'parliaments', pathMatch: 'full' },
  {
    path: 'parliaments',
    loadComponent: () => import('./parliaments-list.component').then((m) => m.ParliamentsListComponent),
    title: 'Taarifu Admin — Parliaments',
  },
  {
    path: 'parliaments/new',
    loadComponent: () => import('./parliament-form.component').then((m) => m.ParliamentFormComponent),
    title: 'Taarifu Admin — New Parliament',
  },
  {
    path: 'parliaments/:parliamentId/edit',
    loadComponent: () => import('./parliament-form.component').then((m) => m.ParliamentFormComponent),
    title: 'Taarifu Admin — Edit Parliament',
  },
  {
    path: 'parliament-roles',
    loadComponent: () => import('./parliament-roles-list.component').then((m) => m.ParliamentRolesListComponent),
    title: 'Taarifu Admin — Parliament Roles',
  },
  {
    path: 'parliament-roles/new',
    loadComponent: () => import('./parliament-role-form.component').then((m) => m.ParliamentRoleFormComponent),
    title: 'Taarifu Admin — New Parliament Role',
  },
  {
    path: 'parliament-roles/:roleId/edit',
    loadComponent: () => import('./parliament-role-form.component').then((m) => m.ParliamentRoleFormComponent),
    title: 'Taarifu Admin — Edit Parliament Role',
  },
  {
    path: 'representatives/new',
    loadComponent: () => import('./representative-form.component').then((m) => m.RepresentativeFormComponent),
    title: 'Taarifu Admin — Link Representative',
  },
];
