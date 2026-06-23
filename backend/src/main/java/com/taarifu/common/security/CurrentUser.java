package com.taarifu.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The authenticated principal carried in the security context after JWT validation
 * (ADR-0007, ARCHITECTURE.md §6.2).
 *
 * <p>Responsibility: a small immutable view of "who is calling" — their immutable {@code publicId}
 * (the JWT subject), role names, and trust tier — used by method-security expressions, the
 * scope-checker, and {@link com.taarifu.common.persistence.AuditorAwareImpl} for audit attribution.</p>
 *
 * <p>WHY {@code publicId} (UUID) is the identity, not a username: usernames/handles are mutable and
 * must never be an authorization key (ADR-0006). The {@code roles}/{@code trustTier} here are derived
 * from the token as <b>hints</b>; binding/high-stakes actions re-resolve the live tier and scope
 * server-side (PRD §18, D13/D18).</p>
 *
 * @param publicId  the user's immutable public id (JWT {@code sub}).
 * @param roles     the user's role names (additive, single account — §6.4).
 * @param trustTier the user's trust-tier name (T0–T3); hint, re-checked for binding actions.
 */
public record CurrentUser(
        UUID publicId,
        List<String> roles,
        String trustTier
) {

    /**
     * Reads the authenticated {@link CurrentUser} from the security context.
     *
     * <p>WHY this lives here (not duplicated in every service): the {@link JwtAuthenticationFilter}
     * stores the rich principal as the authentication <i>details</i>; this accessor is the single,
     * DRY way for aspects/guards/services to recover it. Returns empty when unauthenticated — the
     * caller decides whether that is an error (deny-by-default).</p>
     *
     * @return the current authenticated user, or {@link Optional#empty()} if no JWT principal is present.
     */
    public static Optional<CurrentUser> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getDetails() instanceof CurrentUser cu) {
            return Optional.of(cu);
        }
        return Optional.empty();
    }

    /**
     * @return the authenticated user's {@code publicId}.
     * @throws IllegalStateException if there is no authenticated principal — call only on guarded paths.
     */
    public static UUID requirePublicId() {
        return current()
                .map(CurrentUser::publicId)
                .orElseThrow(() -> new IllegalStateException("No authenticated principal in context"));
    }
}
