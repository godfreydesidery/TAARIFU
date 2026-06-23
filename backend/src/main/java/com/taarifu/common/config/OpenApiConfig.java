package com.taarifu.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * springdoc/OpenAPI configuration (ARCHITECTURE.md §5.4, ADR-0009 contract tests).
 *
 * <p>Responsibility: declares the API document metadata, the {@code /api/v1} server, and a
 * <b>bearer-JWT</b> security scheme placeholder so the generated spec and Swagger UI advertise the
 * authentication model the auth increment will enforce (ADR-0007). The spec is served at
 * {@code /api/v1/openapi.json} (configured in {@code application.yml}) and committed under
 * {@code /docs/api/} for client contract tests.</p>
 *
 * <p>WHY the security scheme is declared now though no endpoint requires auth yet: the geography reads
 * are public, but pinning the {@code bearerAuth} scheme keeps the contract stable so adding
 * {@code @PreAuthorize} later does not churn the generated client (CLAUDE.md §2 contract-first).</p>
 */
@Configuration
public class OpenApiConfig {

    /** Name of the bearer-token security scheme referenced by secured operations. */
    private static final String BEARER_SCHEME = "bearerAuth";

    /**
     * @return the OpenAPI document describing the Taarifu API surface.
     */
    @Bean
    public OpenAPI taarifuOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Taarifu API")
                        .description("Tanzania civic-engagement platform — backend API (modular monolith). "
                                + "Swahili-first; one response envelope; UUID public ids.")
                        .version("v1")
                        .contact(new Contact().name("Taarifu Engineering").email("engineering@taarifu.example"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url("/api/v1").description("Versioned API root")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Stateless JWT access token (ADR-0007). "
                                        + "Public reference reads require no token.")));
    }
}
