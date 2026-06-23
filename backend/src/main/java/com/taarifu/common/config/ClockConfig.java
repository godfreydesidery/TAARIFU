package com.taarifu.common.config;

import com.taarifu.common.domain.port.ClockPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

/**
 * Provides the production {@link ClockPort} bean (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: binds the {@link ClockPort} interface to the real system clock for normal
 * operation. Tests override this bean with a fixed-instant adapter to assert effective-dated
 * resolution and expiry logic deterministically (ADR-0009).</p>
 *
 * <p>WHY a lambda bean (not a class): the production adapter is a one-line delegation to
 * {@link Instant#now()}; a {@code @Bean} keeps it injectable and overridable without a dedicated class
 * (KISS).</p>
 */
@Configuration
public class ClockConfig {

    /**
     * @return the system clock adapter returning the current UTC instant.
     */
    @Bean
    public ClockPort clockPort() {
        return Instant::now;
    }
}
