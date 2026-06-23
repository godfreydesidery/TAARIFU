package com.taarifu.ussd.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * Module configuration anchor for {@code com.taarifu.ussd} (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: a home for this module's bean wiring. The consumer-owned ports
 * ({@code UssdIdentityPort}, {@code UssdReportingPort}, {@code UssdSmsSender}) are each satisfied by a
 * single default stub adapter component today, so no explicit wiring is needed yet — the class exists to
 * lock the package and make future config (real aggregator adapter selection by profile/property, a
 * scheduled session-TTL sweep) a localised change (KISS). The session-store sweep
 * ({@code UssdSessionStore.sweepExpired}) can be promoted to a {@code @Scheduled} task here when scheduling
 * is enabled centrally.</p>
 */
@Configuration
public class UssdConfig {
}
