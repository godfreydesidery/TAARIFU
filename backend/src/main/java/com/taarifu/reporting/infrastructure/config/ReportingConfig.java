package com.taarifu.reporting.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * Module configuration anchor for {@code com.taarifu.reporting} (ARCHITECTURE.md §3.1).
 *
 * <p>Responsibility: a placeholder {@code @Configuration} that gives the reporting module a home for its
 * bean wiring. The {@link com.taarifu.reporting.domain.port.WardResolver} adapter
 * ({@code GeographyWardResolver}) and the services/mappers are component-scanned, so no explicit wiring is
 * needed yet — this class exists to lock the package and make future module config a localised change
 * (KISS, mirroring {@code geography.GeographyConfig}).</p>
 *
 * <p>DEFERRED (later increments wired here): the report→responder routing engine (D21), the SLA-breach
 * escalation scheduler, the transactional-outbox emission of report domain events (ack/status-change
 * fan-out, notifications, search indexing), and the attachment virus-scan hook.</p>
 */
@Configuration
public class ReportingConfig {
}
