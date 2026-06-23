package com.taarifu.geography.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * Module configuration anchor for {@code com.taarifu.geography} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: a placeholder {@code @Configuration} that gives the geography module a home for
 * its bean wiring (e.g. selecting/overriding adapters, registering module-specific beans). The active
 * {@link com.taarifu.geography.domain.port.Geocoder} adapter is chosen by the
 * {@code taarifu.geography.geocoder} property on each adapter (PostGIS default, stub for dev/test),
 * so no explicit wiring is needed here yet — the class exists to lock the package and make future
 * module config a localised change (KISS).</p>
 */
@Configuration
public class GeographyConfig {
}
