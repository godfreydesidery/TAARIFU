package com.taarifu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the Taarifu modular-monolith backend.
 *
 * <p>Responsibility: bootstraps the single deployable that hosts every
 * {@code com.taarifu.*} feature module (ARCHITECTURE.md §1, §3). Each module is a
 * vertical slice ({@code api → application → domain → infrastructure}); boundaries are
 * enforced by ArchUnit tests rather than by Maven reactor modules at this increment.</p>
 *
 * <p>JPA auditing is enabled in {@link com.taarifu.common.persistence.JpaAuditingConfig}
 * (kept out of the entry point so it can be excluded/overridden in slice tests).</p>
 */
@SpringBootApplication
public class TaarifuApplication {

    /**
     * JVM entry point.
     *
     * @param args standard command-line arguments forwarded to Spring Boot.
     */
    public static void main(String[] args) {
        SpringApplication.run(TaarifuApplication.class, args);
    }
}
