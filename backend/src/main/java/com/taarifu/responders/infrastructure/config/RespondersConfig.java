package com.taarifu.responders.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * Module configuration anchor for {@code com.taarifu.responders} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: a placeholder {@code @Configuration} that gives the responders module a home for
 * its bean wiring (e.g. registering the routing-resolution adapter, the outbox publisher for
 * {@code ResponderAssignedEvent}, or a B2B-billing port when Phase 2 lands, §24.4/§24.6). Nothing
 * needs explicit wiring yet — repositories, services, mapper and controllers are component-scanned — so
 * this class exists to lock the package and make future module config a localised change (KISS).</p>
 */
@Configuration
public class RespondersConfig {
}
