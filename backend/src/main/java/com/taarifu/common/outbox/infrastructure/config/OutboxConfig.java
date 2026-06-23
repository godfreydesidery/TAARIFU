package com.taarifu.common.outbox.infrastructure.config;

import com.taarifu.common.outbox.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration anchor for the shared-kernel transactional outbox (ADR-0014, {@code common.outbox}).
 *
 * <p>Responsibility: turns on the two framework capabilities the outbox relay needs and binds its
 * tunables, keeping all outbox wiring localised to this package rather than in the application bootstrap
 * (module-isolation, KISS):</p>
 * <ul>
 *   <li>{@link EnableScheduling} — activates the {@code @Scheduled} poll loop in
 *       {@link com.taarifu.common.outbox.OutboxRelay}. This is the single place scheduling is enabled in
 *       the backend; the relay is currently the only scheduled job.</li>
 *   <li>{@link EnableConfigurationProperties} for {@link OutboxProperties} — binds the optional
 *       {@code taarifu.outbox.*} block (batch size, attempt cap, backoff schedule) with safe defaults so
 *       the outbox works with <b>no central configuration change</b> (the brief's CENTRAL-NEEDS rule).</li>
 * </ul>
 *
 * <p>The poll interval itself ({@code taarifu.outbox.poll-interval-ms}, default 1000ms) is read directly
 * on the relay's {@code @Scheduled} annotation, because annotation attributes must be constant
 * expressions and cannot reference a bound bean.</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {
}
