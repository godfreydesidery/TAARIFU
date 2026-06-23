package com.taarifu;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Shared base for <b>HTTP integration tests that drive the API through {@link
 * org.springframework.test.web.servlet.MockMvc MockMvc}</b> (the security/envelope contract tests).
 *
 * <p>Responsibility: starts the full Spring context on the shared PostGIS Testcontainer (via
 * {@link AbstractPostgisIntegrationTest}) and auto-configures a {@code MockMvc} that <b>honours the
 * production servlet context-path {@code /api/v1}</b>, so a test request to {@code /api/v1/me/wallet}
 * resolves to the controller mapped at {@code /me/wallet} — exactly as it would behind the real
 * container.</p>
 *
 * <p><b>WHY this base exists (the harness defect it fixes).</b> The application sets
 * {@code server.servlet.context-path=/api/v1} (ARCHITECTURE §5.4); controllers therefore map
 * <i>without</i> the prefix (e.g. {@code @RequestMapping("/me/wallet")}). Plain
 * {@code @AutoConfigureMockMvc} mounts its {@code DispatcherServlet} at the root with an <b>empty</b>
 * context-path, so a request to {@code /api/v1/me/wallet} is dispatched with
 * {@code servletPath=/api/v1/me/wallet} and never matches any handler — every such HTTP IT failed with
 * {@code NoResourceFoundException}/500. Setting the context-path per request ({@code .contextPath("/api/v1")})
 * worked but had to be repeated on every call and was easy to forget. This base applies the context-path
 * <b>once, centrally</b>, via a {@link MockMvcBuilderCustomizer} that installs a default request carrying
 * the context-path; MockMvc merges it into every request that does not set its own, so subclasses simply
 * use the full {@code /api/v1/...} URL and the handler resolves.</p>
 *
 * <p>Subclasses inject {@code MockMvc} as usual and issue requests against the full {@code /api/v1/...}
 * path. Tests that call services directly (no HTTP) should extend {@link AbstractPostgisIntegrationTest}
 * instead; tests that prefer a real port can keep using {@code webEnvironment=RANDOM_PORT} +
 * {@code TestRestTemplate} (which already honours the context-path).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AbstractHttpIntegrationTest.MockMvcContextPathConfig.class)
public abstract class AbstractHttpIntegrationTest extends AbstractPostgisIntegrationTest {

    /** The production servlet context-path the API is mounted under (ARCHITECTURE §5.4). */
    public static final String CONTEXT_PATH = "/api/v1";

    /**
     * Test configuration that makes every auto-configured {@code MockMvc} apply the {@code /api/v1}
     * context-path by default.
     *
     * <p>WHY a {@link MockMvcBuilderCustomizer} rather than a hand-built {@code MockMvc}: the customizer
     * hooks the <b>same</b> {@code @AutoConfigureMockMvc} builder Spring Boot already wires (with the full
     * security filter chain, the JWT filter, and the custom 401/403 envelopes), so the central fix changes
     * only the context-path and nothing about the security/envelope wiring the tests assert.</p>
     */
    @TestConfiguration
    static class MockMvcContextPathConfig {

        /**
         * Installs a default request carrying the production context-path on every MockMvc request.
         *
         * <p>MockMvc merges the {@code defaultRequest} into each per-test request, applying the
         * context-path only when the request did not set one — so a subclass calling
         * {@code get("/api/v1/...")} gets {@code servletPath=/...} and the handler resolves.</p>
         *
         * @return the builder customizer applied to the auto-configured {@code MockMvc}.
         */
        @Bean
        MockMvcBuilderCustomizer contextPathCustomizer() {
            return builder -> builder.defaultRequest(get("/").contextPath(CONTEXT_PATH));
        }
    }
}
