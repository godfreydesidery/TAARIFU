/**
 * Token-economy DTOs — mirror the backend `WalletDto`, `TokenTransactionDto`, `ActionCostPolicyDto`,
 * `TokenRewardDto` and their upsert requests (tokens module; PRD §23, M17). These shape the own-wallet
 * ledger view and the admin cost/quota + reward config.
 *
 * <p>Integrity fence (D18, §23 fence): the token economy NEVER buys democratic weight. The admin config
 * here cannot price a binding action — the backend rejects such a policy — and nothing here implies
 * tokens gate a signature/rating/poll. UI copy must reinforce this (no "pay to act" framing).</p>
 */

/** The caller's own wallet (`GET /me/wallet`). */
export interface Wallet {
  /** The wallet's public id (UUID). */
  id: string;
  /** The owning account's public id. */
  ownerId: string;
  /** Current token balance. */
  balance: number;
  /** Wallet status token (e.g. `ACTIVE`). */
  status: string;
}

/** A ledger entry (`GET /me/wallet/ledger`). One per token movement, newest first. */
export interface TokenTransaction {
  /** The transaction's public id (UUID). */
  id: string;
  /** Type token (e.g. `GRANT`, `SPEND`, `REVERSAL`). */
  type: string;
  /** Signed amount (positive credit / negative debit). */
  amount: number;
  /** Resulting balance after this entry. */
  balanceAfter: number;
  /** The action code spent on, or `null`. */
  actionCode: string | null;
  /** Human reason, or `null`. */
  reason: string | null;
  /** Referenced entity type, or `null`. */
  refEntityType: string | null;
  /** Referenced entity id, or `null`. */
  refEntityId: string | null;
  /** Entry instant (ISO-8601). */
  createdAt: string;
}

/** A cost/quota policy (`GET /admin/tokens/policies`). Per (actionCode, role). */
export interface ActionCostPolicy {
  /** The policy's public id (UUID). */
  id: string;
  /** The action code priced (never a binding democratic action — fence D18). */
  actionCode: string;
  /** The role the policy applies to, or `null` for any. */
  roleName: string | null;
  /** Token cost per action (≥ 0). */
  tokenCost: number;
  /** Free-quota window token (e.g. `DAY`, `WEEK`, `MONTH`). */
  freeQuotaPeriod: string;
  /** Free actions allowed per window before cost applies. */
  freeQuotaCount: number;
  /** Policy version (incremented on each supersede). */
  policyVersion: number;
  /** Whether this is the active version. */
  active: boolean;
}

/** A behaviour reward (`GET /admin/tokens/rewards`). */
export interface TokenReward {
  /** The reward's public id (UUID). */
  id: string;
  /** The rewarded behaviour code. */
  behaviour: string;
  /** Tokens granted per occurrence. */
  grantAmount: number;
  /** Max grants per window. */
  capCount: number;
  /** Cap window token (e.g. `DAY`, `WEEK`, `MONTH`). */
  capPeriod: string;
  /** Whether this is the active version. */
  active: boolean;
}

/** Body for `POST /admin/tokens/policies` — create or supersede a cost/quota policy. */
export interface UpsertActionCostPolicy {
  /** The action code (UPPER_SNAKE_CASE). */
  actionCode: string;
  /** The role the policy applies to, or omit for any. */
  roleName?: string;
  /** Token cost per action (≥ 0). */
  tokenCost: number;
  /** Free-quota window token. */
  freeQuotaPeriod: string;
  /** Free actions per window. */
  freeQuotaCount: number;
}

/** Body for `POST /admin/tokens/rewards` — create or supersede a behaviour reward. */
export interface UpsertTokenReward {
  /** The behaviour code. */
  behaviour: string;
  /** Tokens granted per occurrence (> 0). */
  grantAmount: number;
  /** Max grants per window (≥ 0). */
  capCount: number;
  /** Cap window token. */
  capPeriod: string;
}
