package com.taarifu.media.infrastructure.config;

import com.taarifu.media.infrastructure.security.MediaScannerSecretFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

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
 * {@code taarifu.media.s3.*}), {@link MediaPolicyProperties} (the upload allow-list / max size,
 * {@code taarifu.media.policy.*}), and {@link MediaScannerProperties} (the scan-callback service-principal
 * secret, {@code taarifu.media.scan-callback.*}) so all bind without touching the shared application
 * bootstrap — keeping media configuration localised to this module (KISS, module-isolation). It wires the
 * {@link MediaScannerSecretFilter} that authenticates the scanner verdict callback (MF-3).</p>
 */
@Configuration
@EnableConfigurationProperties({MediaStoreProperties.class, MediaPolicyProperties.class,
        MediaScannerProperties.class})
public class MediaConfig {

    /**
     * Registers the {@link MediaScannerSecretFilter} in the servlet filter chain (MF-3).
     *
     * <p>WHY a {@link FilterRegistrationBean} (not a bare {@code @Component} filter): it (a) pins the filter
     * <b>ahead of Spring Security</b> ({@code HIGHEST_PRECEDENCE}) so a forged/unauthenticated scan-callback
     * is rejected as early as possible, and (b) keeps the filter a normal servlet filter rather than being
     * auto-detected into the Spring Security chain — this module must not touch the kernel
     * {@code SecurityConfig}. The filter self-scopes to {@code POST /media/{mediaId}/scan-callback} via
     * {@code shouldNotFilter}, so the broad {@code "/*"} mapping is harmless for every other request.</p>
     *
     * @param properties the externalised scanner secret + header settings.
     * @return the registration binding the scanner secret filter to the servlet chain.
     */
    @Bean
    public FilterRegistrationBean<MediaScannerSecretFilter> mediaScannerSecretFilterRegistration(
            MediaScannerProperties properties) {
        FilterRegistrationBean<MediaScannerSecretFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new MediaScannerSecretFilter(properties));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("mediaScannerSecretFilter");
        return registration;
    }
}
