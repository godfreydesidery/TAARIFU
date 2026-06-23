import { Routes } from '@angular/router';

/**
 * Lazy-loaded issue-category admin routes (UC-B14). List + create + edit, each lazily loaded. The
 * `:categoryId` segment is bound to the form component's `categoryId` input via the router's
 * component-input-binding (configured in app.config.ts) — no manual `ActivatedRoute` plumbing.
 *
 * <p>NOTE: these routes are reachable only via the Admin-gated nav; the SERVER enforces `hasRole('ADMIN')`
 * on every write regardless of client routing (ARCHITECTURE.md §6.2).</p>
 */
export const CATEGORIES_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./categories-list.component').then((m) => m.CategoriesListComponent),
    title: 'Taarifu Admin — Issue Categories',
  },
  {
    path: 'new',
    loadComponent: () => import('./category-form.component').then((m) => m.CategoryFormComponent),
    title: 'Taarifu Admin — New Category',
  },
  {
    path: ':categoryId/edit',
    loadComponent: () => import('./category-form.component').then((m) => m.CategoryFormComponent),
    title: 'Taarifu Admin — Edit Category',
  },
];
