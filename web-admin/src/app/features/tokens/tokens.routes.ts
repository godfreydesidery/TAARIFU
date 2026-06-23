import { Routes } from '@angular/router';

/**
 * Lazy-loaded token-economy routes (PRD §23, M17). Own wallet/ledger view + admin cost/quota/reward
 * config, each lazily loaded.
 *
 * <p>NOTE: the config endpoints are `hasRole('ADMIN')`-gated on the SERVER and cannot price a binding
 * democratic action (integrity fence D18) regardless of client routing.</p>
 */
export const TOKENS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./wallet.component').then((m) => m.WalletComponent),
    title: 'Taarifu Admin — Wallet',
  },
  {
    path: 'config',
    loadComponent: () => import('./token-config.component').then((m) => m.TokenConfigComponent),
    title: 'Taarifu Admin — Token Config',
  },
];
