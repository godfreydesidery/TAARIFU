package com.taarifu.payments.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalised configuration for the mobile-money gateway (ADR-0015; PRD §18, §21 EI-20; CLAUDE.md §12).
 *
 * <p>Responsibility: binds {@code taarifu.payments.gateway.*}. It selects the active adapter
 * ({@link #provider}) and carries the per-rail endpoint, the unit pricing, and a per-request timeout.
 * <b>All secrets (the HMAC callback-verification key, any API credentials) come from the environment /
 * secret manager — never from source</b> (PRD §18, CLAUDE.md §12); this record holds only the bound values,
 * which are blank by default so a no-config context boots on the logging stub.</p>
 *
 * <p>The {@link #provider} property is the single mutually-exclusive selector across the gateway adapters
 * (mirrors the {@code SmsGateway} pattern): {@code logging} (default) | {@code mpesa} | {@code tigopesa} |
 * {@code airtelmoney} | {@code halopesa}. Exactly one {@code MobileMoneyGateway} bean is active in every
 * environment.</p>
 *
 * @param provider       the active adapter selector; defaults to {@code logging} (the safe no-op).
 * @param baseUrl        the rail's HTTPS base URL (collection/verify endpoints); blank on the stub.
 * @param hmacSecret     the per-rail shared secret for callback HMAC verification — <b>env/secret-mount
 *                       only</b>, never committed; blank on the stub.
 * @param signatureHeader the HTTP header the rail presents the callback signature on (default
 *                        {@code X-Signature}).
 * @param priceMinorPerToken the price in minor currency units per token (for server-side pricing);
 *                           {@code 0} on the stub.
 * @param currency       ISO-4217 currency code (default {@code TZS}).
 * @param requestTimeout per-request connect/read timeout so a slow rail never piles up threads (default 8s).
 */
@ConfigurationProperties(prefix = "taarifu.payments.gateway")
public record PaymentsGatewayProperties(
        String provider,
        String baseUrl,
        String hmacSecret,
        String signatureHeader,
        long priceMinorPerToken,
        String currency,
        Duration requestTimeout
) {

    /**
     * Applies safe defaults so a no-config (dev/test/no-profile prod) context boots on the logging stub with
     * zero secrets and no external calls.
     */
    public PaymentsGatewayProperties {
        if (provider == null || provider.isBlank()) {
            provider = "logging";
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            signatureHeader = "X-Signature";
        }
        if (currency == null || currency.isBlank()) {
            currency = "TZS";
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(8);
        }
    }
}
