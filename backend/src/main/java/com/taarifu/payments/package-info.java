/**
 * payments module — mobile-money <b>token top-up</b> (Phase-2 Wave-1, ADR-0015; PRD §23.4/§23.5/§23.6,
 * §25.10, D18).
 *
 * <p>Responsibility: the money-movement bounded context that lets a citizen <b>buy convenience tokens with
 * mobile money</b> (M-Pesa / Tigo Pesa / Airtel Money / HaloPesa). It owns the {@code TopUp} aggregate
 * (initiate → pending → succeeded/failed), the pluggable {@code MobileMoneyGateway} port + per-rail
 * {@code @ConditionalOnProperty} adapters (a logging stub default so dev/CI boot with zero external calls),
 * a signed-webhook ingress (HMAC verify, fail-closed, idempotent on the provider reference), and
 * reconciliation that credits the buyer's token wallet exactly once on a provider-verified settlement.</p>
 *
 * <p><b>🔒 Civic-integrity fence (binding — D18, PRD §23.5):</b> a purchased token tops up the convenience
 * wallet <b>only</b>. It can never buy democratic weight — no petition signature, rating, poll outcome,
 * official routing/SLA/priority, role, or verification status. Binding democratic actions authorise on
 * <i>tier + electoral scope + one-per-person only</i> and never read a token balance. The fence is enforced
 * by construction: this module has no dependency on any binding-action module, its {@code WalletCreditPort}
 * exposes only a top-up credit, and a unit test fails closed if the credit path is anything other than a
 * convenience top-up.</p>
 *
 * <p><b>Boundaries (ADR-0013):</b> payments depends only on the shared kernel {@code common}, its own
 * internals, and other modules' published {@code ..api..} packages. It credits the wallet solely through
 * {@code tokens.api} (the {@code TokensApiWalletCreditAdapter} → {@code TokenLedgerApi.topUp} — a CENTRAL
 * NEED) — never tokens' tables. Owners are referenced by public {@code UUID} only (no cross-module FK). No
 * PII (MSISDN/name) is logged or placed on any event/outbox payload (PRD §18). No secrets in source —
 * gateway endpoints and HMAC keys come from the environment (CLAUDE.md §12).</p>
 *
 * <p>Layering (ARCHITECTURE.md §3.3): {@code api} (controllers, DTOs, the {@code TopUpSucceeded} event) →
 * {@code application} (TopUpService, ReconciliationService) → {@code domain} (the {@code TopUp} entity,
 * repository, the {@code MobileMoneyGateway}/{@code WalletCreditPort} ports) ← {@code infrastructure} (the
 * gateway + wallet-credit adapters, config). Flyway: reserved block {@code V130}–{@code V139}; {@code V130}
 * = {@code top_up}. {@code ddl-auto=validate}.</p>
 *
 * <p><b>CENTRAL NEEDs</b> (owned by other teams; tracked in ADR-0015):
 * (1) {@code tokens.api.TokenLedgerApi.topUp(WalletOwnerType, UUID, long tokenAmount, String idempotencyKey)}
 * + a fence-safe {@code PURCHASE}-credit impl; (2) {@code common.security.SecurityConfig} permitting
 * {@code POST /payments/webhook/**} (the {@code /ussd/gateway} precedent).</p>
 */
package com.taarifu.payments;
