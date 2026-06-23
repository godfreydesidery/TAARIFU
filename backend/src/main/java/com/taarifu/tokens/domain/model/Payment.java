package com.taarifu.tokens.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.tokens.domain.model.enums.PaymentProviderType;
import com.taarifu.tokens.domain.model.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * A token-purchase payment via mobile money / card — <b>Phase 2 stub seam only</b> (PRD §23.4, §23.5,
 * §23.6, §21 EI-20, D19).
 *
 * <p>Responsibility: records the lifecycle of one purchase attempt. Tokens are credited to the wallet (a
 * {@code PURCHASE} {@link TokenTransaction}) <b>only</b> when this payment reaches {@link
 * PaymentStatus#PAID}, and that credit is posted exactly once and reconciled against the provider — never
 * on the unverified callback alone (PRD §23.5 anti-fraud). No real {@link
 * com.taarifu.tokens.domain.port.PaymentProvider} gateway ships in MVP; this entity exists to lock the
 * schema seam so Phase 2 needs no breaking migration (PRD §23.6).</p>
 *
 * <p>WHY {@link #idempotencyKey} is unique and {@link #providerRef} indexed: Tanzanian mobile-money
 * webhooks can arrive duplicated or out of order; the idempotency key dedups our own initiation/retries,
 * and the provider reference lets reconciliation match a settlement to exactly one payment row (PRD §23.5
 * idempotent reconciliation). Amount uses minor units (never a floating-point money value).</p>
 */
@Entity
@Table(name = "payment",
        indexes = {
                @Index(name = "ix_payment_wallet", columnList = "wallet_id"),
                @Index(name = "ix_payment_status", columnList = "status"),
                @Index(name = "ix_payment_provider_ref", columnList = "provider, provider_ref")
        })
@SQLRestriction("deleted = false")
public class Payment extends BaseEntity {
    // INVARIANT (DB-owned, migration V33): idempotency_key is UNIQUE — one business purchase attempt =
    // one row; a replayed initiation is a no-op. providerRef uniqueness per provider is enforced in V33.

    /** The wallet to be credited on settlement (FK). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    /** The package being purchased (FK), or {@code null} for an ad-hoc amount. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id")
    private TokenPackage tokenPackage;

    /** Which mobile-money/card rail settles this payment. */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private PaymentProviderType provider;

    /** The provider's settlement reference (e.g. M-Pesa transaction id); set asynchronously. */
    @Column(name = "provider_ref", length = 128)
    private String providerRef;

    /** Amount paid in the currency's minor units (never a floating-point amount). */
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    /** ISO-4217 currency code (e.g. {@code TZS}). */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "TZS";

    /** Lifecycle state; tokens are credited exactly once on {@link PaymentStatus#PAID}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status = PaymentStatus.PENDING;

    /** Globally-unique key deduping initiation/retries (anti-fraud idempotency, PRD §23.5). */
    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Payment() {
    }

    /**
     * Initiates a pending payment (Phase 2).
     *
     * @param wallet         the wallet to credit on settlement.
     * @param tokenPackage   the package, or {@code null} for ad-hoc.
     * @param provider       settlement rail.
     * @param amountMinor    amount in minor currency units.
     * @param currency       ISO-4217 code.
     * @param idempotencyKey dedup key.
     */
    public Payment(Wallet wallet, TokenPackage tokenPackage, PaymentProviderType provider,
                   long amountMinor, String currency, String idempotencyKey) {
        this.wallet = wallet;
        this.tokenPackage = tokenPackage;
        this.provider = provider;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.PENDING;
    }

    /** @return the wallet to credit. */
    public Wallet getWallet() {
        return wallet;
    }

    /** @return the purchased package, or {@code null}. */
    public TokenPackage getTokenPackage() {
        return tokenPackage;
    }

    /** @return settlement rail. */
    public PaymentProviderType getProvider() {
        return provider;
    }

    /** @return provider settlement reference, or {@code null} until set. */
    public String getProviderRef() {
        return providerRef;
    }

    /** @return amount in minor currency units. */
    public long getAmountMinor() {
        return amountMinor;
    }

    /** @return ISO-4217 currency code. */
    public String getCurrency() {
        return currency;
    }

    /** @return lifecycle status. */
    public PaymentStatus getStatus() {
        return status;
    }

    /** @return the dedup idempotency key. */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** Records the provider reference once known (async settlement). */
    public void setProviderRef(String providerRef) {
        this.providerRef = providerRef;
    }

    /** Transitions the lifecycle status (Phase 2 reconciliation). */
    public void setStatus(PaymentStatus status) {
        this.status = status;
    }
}
