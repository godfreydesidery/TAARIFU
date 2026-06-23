/**
 * tokens module — token economy, metering, free quotas, and the wallet/ledger (M17, PRD §23, D18/D19).
 *
 * <p>Responsibility: a metering + anti-abuse + (Phase 2) monetization layer. It owns the {@code Wallet}, the
 * append-only {@code TokenTransaction} ledger (the source of truth for balances), the admin-tunable
 * {@code ActionCostPolicy}/free-quota and {@code TokenReward} config, the {@code MeteringService} (free quota
 * first, then tokens), idempotent grant/earn, and the Phase 2 purchase seam ({@code TokenPackage},
 * {@code Payment}, {@code PaymentProvider} port + stub).</p>
 *
 * <p><b>🔒 Civic-integrity fence (binding — D18, PRD §23 fence):</b> tokens may meter only convenience,
 * volume, speed, reach, and commercial features. They may <b>never</b> buy democratic weight or truth — no
 * petition signature, rating, poll outcome, official routing/SLA/priority, or verification status. Binding
 * democratic actions authorise on <i>tier + electoral scope + one-per-person only</i> and never read a token
 * balance. The fence is enforced in code: the public {@code com.taarifu.tokens.api.TokenLedgerApi} exposes no
 * balance-gating method, and {@code MeteringService} hard-rejects reserved binding action codes (a unit test
 * fails closed if that guard is removed).</p>
 *
 * <p>Layering (ARCHITECTURE.md §3.3): {@code api} (controllers, DTOs, the public {@code TokenLedgerApi} +
 * events) → {@code application} (services, mapper) → {@code domain} (entities, repositories, the
 * {@code PaymentProvider} port) ← {@code infrastructure} (the stub adapter). Owners are referenced by public
 * {@code UUID} only — no cross-module FK (§3.2). Flyway: V31 (core), V32 (config), V33 (payment seam).</p>
 */
package com.taarifu.tokens;
