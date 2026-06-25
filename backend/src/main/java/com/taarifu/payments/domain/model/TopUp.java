package com.taarifu.payments.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;
import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A mobile-money <b>token top-up</b> — the money-movement lifecycle of one wallet top-up attempt
 * (ADR-0015; PRD §23.4, §23.5, §23.6, §21 EI-20).
 *
 * <p>Responsibility: records initiation → settlement of one purchase and is the reconciliation anchor.
 * Tokens are credited to the buyer's wallet (a {@code PURCHASE} credit via {@code tokens.api}) <b>only</b>
 * when this row reaches {@link TopUpStatus#SUCCEEDED}, and that credit is posted exactly once and reconciled
 * against the provider — <b>never</b> on the unverified callback alone (PRD §23.5 anti-fraud).</p>
 *
 * <p><b>🔒 Civic-integrity fence (D18, PRD §23.5):</b> the credit this row produces is a top-up of the
 * convenience wallet only. It never grants a role, a vote, priority, routing/SLA, or verification status,
 * and a token balance never enters any binding-action authorization path. Tokens buy convenience/reach,
 * never democratic weight.</p>
 *
 * <p><b>Boundary (ADR-0013):</b> {@link #buyerId} and the wallet owner are referenced by opaque public
 * {@code UUID}/kind only — there is <b>no cross-module FK</b> into identity or tokens; the wallet lives in
 * the tokens module and is credited through its published {@code api} port.</p>
 *
 * <p><b>Idempotency (PRD §23.5):</b> {@link #idempotencyKey} is DB-unique (one purchase attempt = one row,
 * so a replayed {@code initiate} is a no-op); {@code (provider, provider_ref)} is partial-unique (one
 * settlement = one row, so a duplicate/out-of-order webhook is a no-op); {@link #creditEventId} keys the
 * wallet credit so a redelivered SUCCEEDED callback credits exactly once. Amount is in <b>minor units</b>
 * (never floating-point money). All DB-owned per migration {@code V130}.</p>
 */
@Entity
@Table(name = "top_up",
        indexes = {
                @Index(name = "ix_top_up_buyer", columnList = "buyer_id"),
                @Index(name = "ix_top_up_status", columnList = "status")
        })
@SQLRestriction("deleted = false")
public class TopUp extends BaseEntity {
    // INVARIANTS (DB-owned, migration V130):
    //   * idempotency_key UNIQUE            — one initiate = one row (replay-safe).
    //   * (provider, provider_ref) UNIQUE WHERE provider_ref IS NOT NULL — one settlement = one row.

    /** The buyer's account public id (opaque UUID — never an FK into identity; ADR-0013). */
    @Column(name = "buyer_id", nullable = false, updatable = false)
    private UUID buyerId;

    /** Which wallet to credit on settlement (USER/ORGANIZATION) — passed to {@code tokens.api}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "wallet_owner_type", nullable = false, length = 16, updatable = false)
    private WalletOwnerKind walletOwnerType;

    /** The mobile-money rail settling this top-up. */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16, updatable = false)
    private MobileMoneyProvider provider;

    /** The provider's settlement correlation reference; set on initiation/callback (reconciliation anchor). */
    @Column(name = "provider_ref", length = 128)
    private String providerRef;

    /** Amount collected in the currency's minor units (never a floating-point money value). */
    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    /** Tokens to credit on SUCCEEDED (priced by catalogue / ad-hoc); always {@code > 0}. */
    @Column(name = "token_amount", nullable = false, updatable = false)
    private long tokenAmount;

    /** ISO-4217 currency code (e.g. {@code TZS}). */
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency = "TZS";

    /** Lifecycle status; tokens are credited exactly once on {@link TopUpStatus#SUCCEEDED}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TopUpStatus status = TopUpStatus.INITIATED;

    /** Globally-unique key deduping initiation/retries (anti-fraud idempotency, PRD §23.5). */
    @Column(name = "idempotency_key", nullable = false, length = 200, updatable = false)
    private String idempotencyKey;

    /** Idempotency key used for the wallet credit; set once, when the credit is posted (exactly-once). */
    @Column(name = "credit_event_id")
    private UUID creditEventId;

    /** Machine failure reason on FAILED; <b>redacted</b> — no PII, no provider body (PRD §18). */
    @Column(name = "failure_reason", length = 256)
    private String failureReason;

    /** JPA requires a no-arg constructor; not for application use. */
    protected TopUp() {
    }

    /**
     * Creates a freshly-initiated top-up (status {@link TopUpStatus#INITIATED}).
     *
     * @param buyerId         buyer account public id (opaque).
     * @param walletOwnerType which wallet to credit.
     * @param provider        the mobile-money rail.
     * @param amountMinor     amount to collect, in minor currency units ({@code >= 0}).
     * @param tokenAmount     tokens to credit on success ({@code > 0}).
     * @param currency        ISO-4217 code.
     * @param idempotencyKey  dedup key for this initiation.
     */
    public TopUp(UUID buyerId, WalletOwnerKind walletOwnerType, MobileMoneyProvider provider,
                 long amountMinor, long tokenAmount, String currency, String idempotencyKey) {
        this.buyerId = buyerId;
        this.walletOwnerType = walletOwnerType;
        this.provider = provider;
        this.amountMinor = amountMinor;
        this.tokenAmount = tokenAmount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.status = TopUpStatus.INITIATED;
    }

    /** @return buyer account public id. */
    public UUID getBuyerId() {
        return buyerId;
    }

    /** @return which wallet to credit. */
    public WalletOwnerKind getWalletOwnerType() {
        return walletOwnerType;
    }

    /** @return the mobile-money rail. */
    public MobileMoneyProvider getProvider() {
        return provider;
    }

    /** @return the provider settlement reference, or {@code null} until set. */
    public String getProviderRef() {
        return providerRef;
    }

    /** @return amount in minor currency units. */
    public long getAmountMinor() {
        return amountMinor;
    }

    /** @return tokens to credit on success. */
    public long getTokenAmount() {
        return tokenAmount;
    }

    /** @return ISO-4217 currency code. */
    public String getCurrency() {
        return currency;
    }

    /** @return lifecycle status. */
    public TopUpStatus getStatus() {
        return status;
    }

    /** @return the dedup idempotency key. */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @return the wallet-credit idempotency key once the credit is posted, else {@code null}. */
    public UUID getCreditEventId() {
        return creditEventId;
    }

    /** @return the redacted failure reason, or {@code null}. */
    public String getFailureReason() {
        return failureReason;
    }

    /**
     * Records the provider reference and moves the row to {@link TopUpStatus#PENDING} once the rail accepts
     * the collection. No-op state-wise if already terminal.
     *
     * @param providerRef the provider's settlement correlation reference.
     */
    public void markPending(String providerRef) {
        this.providerRef = providerRef;
        if (this.status == TopUpStatus.INITIATED) {
            this.status = TopUpStatus.PENDING;
        }
    }

    /**
     * Marks the top-up SUCCEEDED and records the credit idempotency key, exactly once.
     *
     * <p>WHY guarded by {@link #isTerminal()}: a duplicate/out-of-order callback on an already-SUCCEEDED row
     * must not re-credit (idempotent reconciliation, PRD §23.5). The caller checks {@link #isTerminal()}
     * before crediting; this method only flips the in-memory state.</p>
     *
     * @param creditEventId the idempotency key under which the wallet credit was posted.
     */
    public void markSucceeded(UUID creditEventId) {
        this.status = TopUpStatus.SUCCEEDED;
        this.creditEventId = creditEventId;
    }

    /**
     * Marks the top-up FAILED with a redacted reason.
     *
     * @param reason machine reason (never PII, never a provider body).
     */
    public void markFailed(String reason) {
        this.status = TopUpStatus.FAILED;
        this.failureReason = reason;
    }

    /** @return {@code true} if the row is in a terminal state (SUCCEEDED or FAILED) — no further transition. */
    public boolean isTerminal() {
        return this.status == TopUpStatus.SUCCEEDED || this.status == TopUpStatus.FAILED;
    }
}
