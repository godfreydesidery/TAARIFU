import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';

/**
 * Top-level route table for the admin console.
 *
 * <p>Structure: a PUBLIC `/login` route, then a guarded SHELL route ({@link ShellComponent}) that wraps
 * every authenticated feature as lazily-loaded children. The {@link authGuard} protects the shell so an
 * unauthenticated user is redirected to login (a client convenience — the server is the real gate).
 * Every feature is `loadComponent`/`loadChildren` lazy so the initial bundle stays minimal for low-end
 * devices and slow links (PRD §15, bundle budget).</p>
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login.component').then((m) => m.LoginComponent),
    title: 'Taarifu Admin — Login',
  },
  {
    path: '',
    loadComponent: () => import('./layout/shell/shell.component').then((m) => m.ShellComponent),
    canActivate: [authGuard],
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
        title: 'Taarifu Admin — Dashboard',
      },
      {
        path: 'geography',
        loadChildren: () => import('./features/geography/geography.routes').then((m) => m.GEOGRAPHY_ROUTES),
      },
      {
        path: 'representatives',
        loadComponent: () =>
          import('./features/institutions/representatives-list.component').then(
            (m) => m.RepresentativesListComponent,
          ),
        title: 'Taarifu Admin — Representatives',
      },
      {
        path: 'parties',
        loadComponent: () =>
          import('./features/institutions/parties-list.component').then((m) => m.PartiesListComponent),
        title: 'Taarifu Admin — Parties',
      },
      {
        path: 'issue-categories',
        loadChildren: () => import('./features/categories/categories.routes').then((m) => m.CATEGORIES_ROUTES),
      },
    ],
  },
  // Unknown paths → dashboard (the guard redirects to login if unauthenticated).
  { path: '**', redirectTo: '' },
];
