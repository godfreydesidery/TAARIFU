package com.taarifu.payments.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Module configuration for payments (ADR-0015; ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: enables the externalised {@link PaymentsGatewayProperties} binding. The gateway and
 * wallet-credit adapter beans self-register via {@code @Component} + {@code @ConditionalOnProperty}
 * (mirroring the {@code communications} channel adapters), so this class stays minimal — it exists only to
 * activate the properties binding for the module.</p>
 */
@Configuration
@EnableConfigurationProperties(PaymentsGatewayProperties.class)
public class PaymentsConfig {
}
