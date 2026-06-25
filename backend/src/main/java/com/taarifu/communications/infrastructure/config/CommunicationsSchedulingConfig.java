package com.taarifu.communications.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers this wave's added communications configuration records — the announcement-expiry sweep's
 * tunables and the inbound SMS delivery-report webhook's settings (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: bind {@link AnnouncementExpiryProperties}
 * ({@code taarifu.communications.announcement-expiry.*} — the {@code @Scheduled} expiry sweep's
 * enable-toggle, cron, zone, grace) and {@link SmsDlrProperties} ({@code taarifu.communications.sms.dlr.*} —
 * the DLR webhook's shared secret + payload field names). It is a sibling of the existing
 * {@code CommunicationsConfig} (which binds the channel + digest properties); this second anchor keeps the
 * new records' wiring localised to this module without modifying the existing config class.</p>
 *
 * <p>WHY this module declares no {@code @EnableScheduling}: scheduling is enabled once, centrally, by
 * {@code com.taarifu.common.outbox.infrastructure.config.OutboxConfig}; the expiry sweep's
 * {@code @Scheduled} method is picked up by that single enablement (DRY — a second
 * {@code @EnableScheduling} would be redundant). Both records carry safe defaults, so this binding needs
 * <b>no central configuration change</b> to exist (the brief's CENTRAL-NEEDS rule).</p>
 */
@Configuration
@EnableConfigurationProperties({AnnouncementExpiryProperties.class, SmsDlrProperties.class})
public class CommunicationsSchedulingConfig {
}
