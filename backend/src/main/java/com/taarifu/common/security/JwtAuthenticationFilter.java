package com.taarifu.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates requests bearing a {@code Authorization: Bearer <jwt>} access token
 * (ADR-0007, ARCHITECTURE.md §6).
 *
 * <p>Responsibility: on each request, if a well-formed bearer token is present and verifies as an
 * {@link TokenType#ACCESS} token, it places a {@link CurrentUser} principal (keyed by the user's
 * {@code publicId}) and the user's role authorities into the {@link SecurityContextHolder}. Absence
 * of a token is <b>not</b> an error here — public reference reads proceed unauthenticated and
 * authorization is decided later by method security (deny-by-default).</p>
 *
 * <p>WHY this is a once-per-request filter that never writes a response: it only <i>populates</i> the
 * context. Rejecting unauthorized access is the job of {@code @PreAuthorize} + the
 * {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler}, so authorization stays in one place
 * (ARCHITECTURE.md §6.2). Roles are prefixed {@code ROLE_} to match Spring's {@code hasRole(...)}.</p>
 *
 * <p>WHY a verification failure is swallowed (context simply left empty): an invalid/expired token is
 * treated as "unauthenticated", and the downstream entry point returns the uniform
 * {@code UNAUTHENTICATED} envelope — the filter never leaks <i>why</i> a token was rejected (PRD §18).</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    /**
     * @param jwtService verifier used to validate access tokens.
     */
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * Extracts and validates the bearer token, populating the security context on success.
     *
     * @param request     the inbound request.
     * @param response    the response (untouched by this filter).
     * @param filterChain the remaining chain.
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractBearer(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                JwtService.ParsedToken parsed = jwtService.verify(token, TokenType.ACCESS);
                authenticate(request, parsed);
            } catch (JwtVerificationException ignored) {
                // Leave the context empty → treated as unauthenticated; do not block the chain or leak why.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Places the validated principal + role authorities into the security context. */
    private void authenticate(HttpServletRequest request, JwtService.ParsedToken parsed) {
        List<SimpleGrantedAuthority> authorities = parsed.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        CurrentUser principal = new CurrentUser(parsed.subject(), parsed.roles(), parsed.trustTier());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal.publicId(), null, authorities);
        // Carry the rich principal as details so the scope-checker can read roles/tier without re-parsing.
        authentication.setDetails(principal);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** Returns the raw token from the {@code Authorization} header, or {@code null} if absent/malformed. */
    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
