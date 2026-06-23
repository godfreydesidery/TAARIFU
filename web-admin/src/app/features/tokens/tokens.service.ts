import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client.service';
import { Page } from '../../core/api/api-response.model';
import {
  ActionCostPolicy,
  TokenReward,
  TokenTransaction,
  UpsertActionCostPolicy,
  UpsertTokenReward,
  Wallet,
} from './tokens.models';

/**
 * Data access for the token economy — own wallet/ledger + admin cost/quota/reward config (PRD §23, M17).
 *
 * <p>Responsibility: the feature's typed gateway over `/me/wallet*` (own balance/ledger, read-only) and
 * `/admin/tokens/*` (cost/quota policies + behaviour rewards, ADMIN-gated). The wallet read is own-only on
 * the server (owner derived from the JWT, never a param). The admin config CANNOT price a binding
 * democratic action — the backend rejects such a policy (integrity fence D18). Envelope/error handling is
 * delegated to {@link ApiClient} (DRY, CLAUDE.md §8).</p>
 */
@Injectable({ providedIn: 'root' })
export class TokensService {
  private readonly api = inject(ApiClient);

  /**
   * Fetches the caller's own wallet. `GET /me/wallet`.
   * @returns the {@link Wallet}.
   */
  getMyWallet(): Observable<Wallet> {
    return this.api.get<Wallet>('/me/wallet');
  }

  /**
   * Fetches the caller's own ledger, newest first, paged. `GET /me/wallet/ledger`.
   * @param params optional `page`/`size`/`sort`.
   * @returns a {@link Page} of {@link TokenTransaction}.
   */
  getMyLedger(params: { page?: number; size?: number; sort?: string }): Observable<Page<TokenTransaction>> {
    return this.api.getPage<TokenTransaction>('/me/wallet/ledger', params);
  }

  /**
   * Lists active cost/quota policies. `GET /admin/tokens/policies`.
   * @returns the active {@link ActionCostPolicy} list.
   */
  listPolicies(): Observable<ActionCostPolicy[]> {
    return this.api.get<ActionCostPolicy[]>('/admin/tokens/policies');
  }

  /**
   * Creates or supersedes a cost/quota policy. `POST /admin/tokens/policies`.
   * @param body the validated upsert request.
   * @returns the new active {@link ActionCostPolicy}.
   */
  upsertPolicy(body: UpsertActionCostPolicy): Observable<ActionCostPolicy> {
    return this.api.post<ActionCostPolicy, UpsertActionCostPolicy>('/admin/tokens/policies', body);
  }

  /**
   * Retires (deactivates) a cost/quota policy. `DELETE /admin/tokens/policies/{id}`.
   * @param policyId the policy's public id.
   * @returns `void` on success.
   */
  deactivatePolicy(policyId: string): Observable<void> {
    return this.api.del(`/admin/tokens/policies/${policyId}`);
  }

  /**
   * Lists active behaviour rewards. `GET /admin/tokens/rewards`.
   * @returns the active {@link TokenReward} list.
   */
  listRewards(): Observable<TokenReward[]> {
    return this.api.get<TokenReward[]>('/admin/tokens/rewards');
  }

  /**
   * Creates or supersedes a behaviour reward. `POST /admin/tokens/rewards`.
   * @param body the validated upsert request.
   * @returns the new active {@link TokenReward}.
   */
  upsertReward(body: UpsertTokenReward): Observable<TokenReward> {
    return this.api.post<TokenReward, UpsertTokenReward>('/admin/tokens/rewards', body);
  }

  /**
   * Retires (deactivates) a behaviour reward. `DELETE /admin/tokens/rewards/{id}`.
   * @param rewardId the reward's public id.
   * @returns `void` on success.
   */
  deactivateReward(rewardId: string): Observable<void> {
    return this.api.del(`/admin/tokens/rewards/${rewardId}`);
  }
}
