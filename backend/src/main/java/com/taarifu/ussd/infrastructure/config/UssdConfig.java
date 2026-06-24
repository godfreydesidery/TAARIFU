package com.taarifu.ussd.infrastructure.config;

import com.taarifu.ussd.infrastructure.security.UssdGatewaySecretFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Module configuration anchor for {@code com.taarifu.ussd} (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: a home for this module's bean wiring. The consumer-owned ports
 * ({@code UssdIdentityPort}, {@code UssdReportingPort}, {@code UssdSmsSender}) are each satisfied by a
 * single default stub adapter component today. It registers {@link UssdGatewayProperties} (the externalised
 * aggregator shared-secret settings) and the {@link UssdGatewaySecretFilter} that authenticates the open
 * USSD webhook (wave2-review P2-1). The session-store sweep ({@code UssdSessionStore.sweepExpired}) can be
 * promoted to a {@code @Scheduled} task here when scheduling is enabled centrally.</p>
 */
@Configuration
@EnableConfigurationProperties(UssdGatewayProperties.class)
public class UssdConfig {

    /**
     * Registers the {@link UssdGatewaySecretFilter} in the servlet filter chain.
     *
     * <p>WHY a {@link FilterRegistrationBean} (not a bare {@code @Component} filter): it (a) pins the
     * filter <b>ahead of Spring Security</b> ({@code HIGHEST_PRECEDENCE}) so an unauthenticated aggregator
     * call is rejected as early as possible, and (b) keeps the filter a normal servlet filter rather than
     * being auto-detected into the Spring Security chain — this module must not touch the kernel
     * {@code SecurityConfig}. The filter self-scopes to {@code POST /ussd/gateway} via
     * {@code shouldNotFilter}, so the broad {@code "/*"} mapping is harmless for every other request.</p>
     *
     * @param properties the externalised aggregator secret + header settings.
     * @return the registration binding the secret filter to the servlet chain.
     */
    @Bean
    public FilterRegistrationBean<UssdGatewaySecretFilter> ussdGatewaySecretFilterRegistration(
            UssdGatewayProperties properties) {
        FilterRegistrationBean<UssdGatewaySecretFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new UssdGatewaySecretFilter(properties));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("ussdGatewaySecretFilter");
        return registration;
    }
}
