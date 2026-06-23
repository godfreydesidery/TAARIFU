import { Routes } from '@angular/router';

/**
 * Lazy-loaded responder-directory routes (PRD §24). Organisations list + create/edit, organisation
 * detail (its responders), and the routing-rules view. The `:organisationId` segment is bound to the
 * form/detail component inputs via the router's component-input-binding.
 *
 * <p>NOTE: every write/verify is ADMIN/MODERATOR-gated on the SERVER regardless of client routing
 * (ARCHITECTURE.md §6.2).</p>
 */
export const RESPONDERS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./organisations-list.component').then((m) => m.OrganisationsListComponent),
    title: 'Taarifu Admin — Responders',
  },
  {
    path: 'new',
    loadComponent: () => import('./organisation-form.component').then((m) => m.OrganisationFormComponent),
    title: 'Taarifu Admin — New Organisation',
  },
  {
    path: 'routing-rules',
    loadComponent: () => import('./routing-rules.component').then((m) => m.RoutingRulesComponent),
    title: 'Taarifu Admin — Routing Rules',
  },
  {
    path: ':organisationId',
    loadComponent: () => import('./organisation-detail.component').then((m) => m.OrganisationDetailComponent),
    title: 'Taarifu Admin — Organisation',
  },
  {
    path: ':organisationId/edit',
    loadComponent: () => import('./organisation-form.component').then((m) => m.OrganisationFormComponent),
    title: 'Taarifu Admin — Edit Organisation',
  },
];
