package com.taarifu.payments.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Module configuration for payments (ADR-0015; ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: enables the externalised {@link PaymentsGatewayProperties} and
 * {@link PaymentsReconciliationProperties} bindings. The gateway, wallet-credit, and wallet-reversal adapter
 * beans self-register via {@code @Component} + {@code @ConditionalOnProperty} (mirroring the
 * {@code communications} channel adapters), so this class stays minimal — it exists only to activate the
 * properties bindings for the module.</p>
 *
 * <p>The {@code @Scheduled} reconciliation job's {@code @EnableScheduling} is provided centrally by the
 * shared-kernel {@code common.outbox.infrastructure.config.OutboxConfig} (the single scheduling switch in the
 * backend), so the payments job needs no module-level scheduling enablement — only its
 * {@link PaymentsReconciliationProperties} toggle (CENTRAL-NEEDS rule: no central config change required).</p>
 */
@Configuration
@EnableConfigurationProperties({PaymentsGatewayProperties.class, PaymentsReconciliationProperties.class})
public class PaymentsConfig {
}
