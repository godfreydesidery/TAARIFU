package com.taarifu.communications.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Module configuration anchor for {@code com.taarifu.communications} (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: a home for this module's bean wiring (adapter selection, worker registration). The
 * channel ports ({@code SmsGateway}/{@code PushSender}/{@code EmailSender}) select their adapter by
 * {@code @ConditionalOnProperty} on each adapter — a real adapter when its
 * {@code taarifu.communications.{sms,push,email}.provider} is set ({@code http}/{@code fcm}/{@code smtp}),
 * and a safe <b>logging no-op default</b> ({@code matchIfMissing}) otherwise — so exactly one bean per
 * port resolves in <b>every</b> environment, including a no-profile production context (the gap that
 * previously stopped prod from booting on the missing {@code SmsGateway} bean).</p>
 *
 * <p>It registers {@link CommunicationsChannelProperties} so the real adapters' non-secret connection
 * settings ({@code taarifu.communications.*}) bind without touching the shared application bootstrap —
 * keeping all communications channel configuration localised to this module (KISS, module-isolation).</p>
 */
@Configuration
@EnableConfigurationProperties(CommunicationsChannelProperties.class)
public class CommunicationsConfig {
}
