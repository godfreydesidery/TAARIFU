package com.taarifu.institutions.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * Module configuration anchor for {@code com.taarifu.institutions} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: a placeholder {@code @Configuration} that gives the institutions module a home for
 * future bean wiring (e.g. outbox event publishers when representative changes fan out to the feed, or
 * an adapter selection). The read/write services, mapper, and controllers are picked up by the
 * application's {@code com.taarifu} component scan, so no explicit wiring is needed yet — the class
 * exists to lock the package and keep future module config a localised change (KISS, CLAUDE.md §3).</p>
 */
@Configuration
public class InstitutionsConfig {
}
