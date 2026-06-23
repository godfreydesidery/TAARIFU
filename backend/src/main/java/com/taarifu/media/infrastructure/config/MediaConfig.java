package com.taarifu.media.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * Module configuration anchor for {@code com.taarifu.media} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: a placeholder {@code @Configuration} that gives the media module a home for its
 * bean wiring. The active {@link com.taarifu.media.domain.port.ObjectStore} and
 * {@link com.taarifu.media.domain.port.MalwareScanner} adapters are selected by the
 * {@code taarifu.media.object-store} and {@code taarifu.media.scanner} properties on each adapter
 * (stub by default; the S3 / real-scanner adapters land with their deferred dependencies), so no
 * explicit wiring is needed here yet — the class exists to lock the package and make future module
 * config a localised change (KISS).</p>
 */
@Configuration
public class MediaConfig {
}
