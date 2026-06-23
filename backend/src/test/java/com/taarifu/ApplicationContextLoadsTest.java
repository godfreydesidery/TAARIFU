package com.taarifu;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test asserting the full Spring application context starts (ADR-0009, CLAUDE.md §10).
 *
 * <p>Responsibility: the cheapest guard against wiring regressions — if any bean (security, JPA
 * auditing, the geocoder adapter selection, the crypto converter bridge, OpenAPI, etc.) is
 * mis-configured, this fails. It boots against a real PostGIS Testcontainer (schema generated from
 * entities under the {@code test} profile) so entity/mapping problems surface here too.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextLoadsTest extends AbstractPostgisIntegrationTest {

    /** Asserts the context loads; an empty body is intentional — failure is a context startup error. */
    @Test
    void contextLoads() {
        // No assertions: the test passes iff the application context starts successfully.
    }
}
