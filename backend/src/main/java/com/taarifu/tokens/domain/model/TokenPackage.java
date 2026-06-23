package com.taarifu.tokens.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.tokens.domain.model.enums.PackageAudience;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * A purchasable bundle of tokens at a fixed price — <b>Phase 2 seam only</b> (PRD §23.4, §23.6, D19).
 *
 * <p>Responsibility: the catalogue row a citizen/org/provider buys. Modelled now so the purchase seam is
 * in the schema (no later breaking migration), but <b>no purchase flow ships in MVP</b> — the free path is
 * always sufficient (PRD §23.5 accessibility, §23.6).</p>
 *
 * <p>WHY price is stored as a minor-unit {@code long} ({@link #priceMinor}) with a currency code: money is
 * never a floating-point {@code double} (rounding corruption); TZS has no minor unit in practice but the
 * minor-unit discipline keeps the model correct for any currency and for card payments (paranoid-on-money,
 * standard fintech practice).</p>
 */
@Entity
@Table(name = "token_package",
        indexes = {
                @Index(name = "ix_token_package_audience", columnList = "audience, active")
        })
@SQLRestriction("deleted = false")
public class TokenPackage extends BaseEntity {

    /** Display name of the pack (e.g. "Kifurushi Kidogo"). */
    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Number of tokens credited on a successful purchase of this pack. Positive. */
    @Column(name = "token_amount", nullable = false)
    private long tokenAmount;

    /** Price in the currency's minor units (never a floating-point amount). */
    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    /** ISO-4217 currency code (e.g. {@code TZS}). */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "TZS";

    /** The buyer segment this pack targets. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private PackageAudience audience;

    /** Whether the pack is purchasable. */
    @Column(name = "active", nullable = false)
    private boolean active = false;

    /** JPA requires a no-arg constructor; not for application use. */
    protected TokenPackage() {
    }

    /**
     * Defines a token package (Phase 2 catalogue).
     *
     * @param name        display name.
     * @param tokenAmount tokens credited on purchase.
     * @param priceMinor  price in minor currency units.
     * @param currency    ISO-4217 code.
     * @param audience    buyer segment.
     */
    public TokenPackage(String name, long tokenAmount, long priceMinor, String currency,
                        PackageAudience audience) {
        this.name = name;
        this.tokenAmount = tokenAmount;
        this.priceMinor = priceMinor;
        this.currency = currency;
        this.audience = audience;
        this.active = false;
    }

    /** @return display name. */
    public String getName() {
        return name;
    }

    /** @return tokens credited on purchase. */
    public long getTokenAmount() {
        return tokenAmount;
    }

    /** @return price in minor currency units. */
    public long getPriceMinor() {
        return priceMinor;
    }

    /** @return ISO-4217 currency code. */
    public String getCurrency() {
        return currency;
    }

    /** @return buyer segment. */
    public PackageAudience getAudience() {
        return audience;
    }

    /** @return whether purchasable. */
    public boolean isActive() {
        return active;
    }

    /** Activates/deactivates the pack (admin). */
    public void setActive(boolean active) {
        this.active = active;
    }
}
