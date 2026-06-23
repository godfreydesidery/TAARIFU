package com.taarifu.tokens.domain.model.enums;

/**
 * The kind of principal that owns a {@link com.taarifu.tokens.domain.model.Wallet} (PRD §23.1, §23.4).
 *
 * <p>Responsibility: discriminates whether a wallet belongs to an individual citizen account or an
 * organisation/service-provider account, so metering, free-quota policy lookups, and (Phase 2) package
 * audiences can differ by owner class.</p>
 *
 * <p>WHY a {@code (ownerType, ownerId)} pair rather than a hard FK to {@code User}/{@code Organisation}:
 * those entities live in other modules (identity/responders). The tokens module references owners by
 * their public {@code UUID} and never reaches into another module's tables (ARCHITECTURE.md §3.2). The
 * pair keeps the wallet self-contained and resolvable through each module's public API.</p>
 */
public enum WalletOwnerType {

    /** An individual citizen account (an {@code identity} {@code User} public id). */
    USER,

    /**
     * An organisation or service-provider account. PRD §23.1 lists Organisation and ServiceProvider as
     * wallet owners; both are modelled as organisation-class owners here and disambiguated by the
     * owning module via {@code ownerId}, keeping this enum stable as those modules land.
     */
    ORGANIZATION
}
