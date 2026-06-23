package com.taarifu.communications.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * Module configuration anchor for {@code com.taarifu.communications} (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: a home for this module's bean wiring (adapter selection, worker registration). The
 * channel ports ({@code SmsGateway}/{@code PushSender}/{@code EmailSender}) select their adapter by
 * profile/property on each adapter (dev stubs under {@code dev}/{@code test}; real aggregator/FCM/ESP
 * adapters in the integration increment), so no explicit wiring is needed here yet — the class exists to
 * lock the package and make future module config a localised change (KISS).</p>
 */
@Configuration
public class CommunicationsConfig {
}
