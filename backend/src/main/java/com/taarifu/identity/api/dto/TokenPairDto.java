package com.taarifu.identity.api.dto;

/**
 * The access + refresh token pair returned on signup/login/refresh (AUTH-DESIGN §5.2).
 *
 * <p>WHY this is a thin DTO over the service {@code TokenPair}: it keeps the application type out of the
 * API contract (entities/internal types never leak past a module — CLAUDE.md §8). The raw tokens are
 * returned to the client over TLS and must never be logged; only their hash is stored server-side.</p>
 *
 * @param accessToken  the short-lived (~15 min) access JWT.
 * @param refreshToken the rotating, single-use refresh JWT (~30 days).
 */
public record TokenPairDto(String accessToken, String refreshToken) {
}
