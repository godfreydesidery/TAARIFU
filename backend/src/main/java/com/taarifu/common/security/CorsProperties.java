package com.taarifu.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Externalised CORS allow-list bound from {@code taarifu.security.cors.*} (PRD §18, CLAUDE.md §12).
 *
 * <p>Responsibility: holds the explicit list of permitted browser origins (the Angular admin/web
 * console). <b>Never a wildcard with credentials</b> — that was a legacy vulnerability this design
 * fixes (ARCHITECTURE.md §6.3). Origins come from configuration/environment per deployment, not from
 * source.</p>
 *
 * @param allowedOrigins exact origins permitted to call the API with credentials
 *                       (e.g. {@code https://admin.taarifu.example}). Empty by default — a deployment
 *                       must opt in explicitly.
 */
@ConfigurationProperties(prefix = "taarifu.security.cors")
public record CorsProperties(
        List<String> allowedOrigins
) {

    /** Defaults to an empty allow-list so nothing is permitted unless a deployment configures it. */
    public CorsProperties {
        if (allowedOrigins == null) {
            allowedOrigins = List.of();
        }
    }
}
