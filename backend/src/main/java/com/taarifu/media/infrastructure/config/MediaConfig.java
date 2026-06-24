package com.taarifu.media.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Module configuration anchor for {@code com.taarifu.media} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: gives the media module a home for its bean wiring. The active
 * {@link com.taarifu.media.domain.port.ObjectStore} and {@link com.taarifu.media.domain.port.MalwareScanner}
 * adapters are selected by the {@code taarifu.media.object-store} and {@code taarifu.media.scanner}
 * properties on each adapter (stub by default; the S3 adapter is selected by {@code object-store=s3}),
 * so no explicit adapter wiring is needed here.</p>
 *
 * <p>It also registers {@link MediaStoreProperties} (the S3 adapter's non-secret connection settings,
 * {@code taarifu.media.s3.*}) and {@link MediaPolicyProperties} (the upload allow-list / max size,
 * {@code taarifu.media.policy.*}) so both bind without touching the shared application bootstrap —
 * keeping all media configuration localised to this module (KISS, module-isolation).</p>
 */
@Configuration
@EnableConfigurationProperties({MediaStoreProperties.class, MediaPolicyProperties.class})
public class MediaConfig {
}
