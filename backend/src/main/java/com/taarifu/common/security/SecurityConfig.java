package com.taarifu.common.security;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ErrorDetail;
import com.taarifu.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The application security policy — stateless JWT, deny-by-default, method-level RBAC
 * (ADR-0007, ARCHITECTURE.md §6, CLAUDE.md §3).
 *
 * <p>Responsibility: wires the {@link JwtAuthenticationFilter}, sets a <b>stateless</b> session
 * policy, disables CSRF (irrelevant to a bearer-token API with no cookies), applies a <b>tight CORS
 * allow-list</b> (never wildcard-with-credentials — PRD §18), exposes a {@link BCryptPasswordEncoder},
 * and enables {@code @EnableMethodSecurity} so every protected endpoint is gated by
 * {@code @PreAuthorize} (deny-by-default).</p>
 *
 * <p>Public surface (no token required): reference-data reads ({@code /regions}, {@code /locations},
 * {@code /constituencies}), the OpenAPI docs, and actuator health — per PRD §11 M1 / §22.6 (public
 * scope). Everything else requires authentication; <b>fine-grained</b> authorization is decided by
 * method security, not by URL patterns (so admin surfaces can never be merely "authenticated-only" —
 * the legacy gap, PRD §7.1).</p>
 *
 * <p>WHY auth errors are rendered as the single envelope here too: the {@code authenticationEntryPoint}
 * and {@code accessDeniedHandler} write the same {@link com.taarifu.common.api.dto.ApiResponse} shape
 * so a 401/403 from the security layer is indistinguishable in form from one raised in a controller
 * (ADR-0008 — one envelope, always).</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class})
public class SecurityConfig {

    /**
     * URL patterns served without authentication (public reference reads + ops/docs).
     *
     * <p><b>Paths are context-relative</b> — written WITHOUT the {@code /api/v1} prefix. Spring Security
     * matches against the servlet path <i>after</i> the container strips {@code server.servlet.context-path}
     * (={@code /api/v1}), so a pattern that included {@code /api/v1} would never match and the endpoint
     * would fall through to {@code anyRequest().authenticated()} — a silent 401 on every "public" read.
     * Do not re-add the prefix here.</p>
     *
     * <p>GET-only: write/admin endpoints under these prefixes (POST/PUT/DELETE, or method-gated GETs
     * such as {@code /issue-categories/admin}) remain protected because (a) this allow-list is scoped to
     * {@link HttpMethod#GET} and (b) {@code @PreAuthorize} still runs at the method layer even when the
     * URL is {@code permitAll()} — an anonymous request to an {@code ADMIN}-gated handler is denied.
     * Public civic-data reads per PRD §11/§22.6 + the wave-1 module directories.</p>
     */
    private static final String[] PUBLIC_GET_PATTERNS = {
            // Geography reference data (M1)
            "/regions/**",
            "/districts/**",
            "/councils/**",
            "/wards/**",
            "/locations/**",
            "/constituencies/**",
            // Representatives & institutions directory (M2)
            "/representatives/**",
            "/parties/**",
            "/parliaments/**",
            // Issue categories + public reports (M3)
            "/issue-categories/**",
            "/public/reports/**",
            // Service-provider / responder directory (M18)
            "/responders/**",
            "/organisations/**",
            // Engagement public reads (M8/M9/M10) — drafts/moderation-held excluded service-side
            "/petitions/**",
            "/surveys/**",
            "/questions/**",
            // Public discovery/search (search module, ADR-0017 §4). WHY public: cross-entity discovery of
            // the PUBLIC civic graph is a civic-core read no citizen (including an unauthenticated guest)
            // should be gated out of (PRD §18 inclusion) — mirrors the open `/representatives/**` and
            // `/announcements/*` reads. The real control is NOT this URL filter but a SERVER-SIDE
            // visibility predicate in SearchQueryService: for a guest/non-staff caller the FTS query
            // hard-restricts to `visibility = 'PUBLIC'` (and re-states `deleted = FALSE`), so STAFF /
            // private / sensitive / suspended-author rows are FILTERED OUT of the result set — never 403'd
            // per row (anti-enumeration). A blank query returns an empty page (no corpus dump). Opening
            // this is therefore safe: the URL is auth, the predicate is the gate (SearchController Javadoc).
            "/search/**",
            // Announcements public civic graph (M11) — single-segment so it matches the public
            // detail read `/announcements/{publicId}`; `/announcements/mine` is one segment too but
            // its own @PreAuthorize denies anonymous, so least-privilege still holds. Published +
            // in-window visibility is enforced service-side (404-not-403 to avoid enumeration, §18).
            "/announcements/*"
    };

    /** Patterns served without authentication regardless of method (docs + health). */
    private static final String[] PUBLIC_ANY_PATTERNS = {
            "/openapi.json",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/actuator/health/**",
            "/actuator/info"
    };

    /**
     * Unauthenticated auth endpoints (POST): OTP request/verify, signup, login (password/OTP), and
     * refresh — these mint or rotate tokens and therefore <b>cannot</b> require a prior token
     * (AUTH-DESIGN §14.1). Logout is deliberately NOT here — it requires authentication. Anti-automation
     * (rate-limit/lockout) and anti-enumeration protect these open endpoints (S-2).
     */
    private static final String[] PUBLIC_POST_PATTERNS = {
            "/auth/otp/request",
            "/auth/signup",
            "/auth/login/password",
            "/auth/login/otp",
            "/auth/login/otp/request",
            // Staff second-factor step (N-4): carries an MFA_CHALLENGE token (not a prior access token),
            // so it cannot require authentication; it is anti-automation-protected like the other open
            // login endpoints (AUTH-DESIGN §14.1, VERIFICATION-DESIGN §7.1/§9.5).
            "/auth/login/totp",
            "/auth/refresh",
            // USSD aggregator webhook (W3-1): permitted at the URL filter because there is no user token;
            // UssdGatewaySecretFilter authenticates the aggregator by a fail-closed shared secret and a
            // per-MSISDN rate-limit guards no-OTP account-creation abuse (TR-1/TR-3, wave3-review).
            "/ussd/gateway",
            // Mobile-money settlement webhook (payments module, ADR-0015 §3). WHY public: the aggregator
            // posting a provider callback (e.g. POST /payments/webhook/MPESA) holds NO user JWT — like the
            // /ussd/gateway precedent. The real authentication is the HMAC-SHA256 signature over the RAW
            // body, verified FAIL-CLOSED inside PaymentWebhookController before any state change
            // (AbstractHmacMobileMoneyGateway.verifyCallbackSignature, constant-time compare; an invalid /
            // missing signature → benign 200, no credit, no forgery oracle). Settlement is additionally
            // never-trust-the-callback (provider re-confirmed) and idempotent in ReconciliationService.
            // Without this entry the HMAC-secured endpoint is 401'd at the URL filter and payments cannot
            // settle anonymously. The pattern is `/**` because the rail is a path segment.
            "/payments/webhook/**",
            // SMS delivery-report (DLR) callback (communications module, wave-4 real SMS adapter). WHY public:
            // the SMS aggregator POSTs a delivery receipt with NO user JWT — same precedent as /ussd/gateway
            // and /payments/webhook. The callback is authenticated/validated inside the controller
            // (fail-closed signature/shared-secret check, no PII trust) before any notification-state change.
            // Without this entry the DLR is 401'd at the URL filter and SMS delivery receipts never advance
            // notification state (wave4-review P1).
            "/communications/sms/dlr"
    };

    /**
     * Defines the single security filter chain.
     *
     * @param http            the Spring Security builder.
     * @param jwtService      verifier injected into the JWT filter.
     * @param responseFactory builds the envelope for 401/403 responses.
     * @param objectMapper    serialises the envelope.
     * @param corsProperties  the externalised CORS allow-list.
     * @return the configured {@link SecurityFilterChain}.
     * @throws Exception if the chain cannot be built.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   ResponseFactory responseFactory,
                                                   ObjectMapper objectMapper,
                                                   CorsProperties corsProperties) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);

        http
                // Bearer-token API: no CSRF tokens, no server sessions.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource(corsProperties)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ANY_PATTERNS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATTERNS).permitAll()
                        // Token-minting auth endpoints are open (they cannot require a prior token);
                        // logout + everything else stays authenticated (AUTH-DESIGN §14.1).
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_PATTERNS).permitAll()
                        // Deny-by-default: any other request must at least be authenticated; the
                        // precise permission is then enforced by @PreAuthorize on the handler.
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeEnvelope(response, objectMapper, responseFactory,
                                        ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeEnvelope(response, objectMapper, responseFactory,
                                        ErrorCode.FORBIDDEN)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * @return the BCrypt password encoder used for credential hashing (ADR-0007). Never store or
     *         compare plaintext passwords.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The staff role hierarchy: a higher role inherits the authorities of the lower ones, so a
     * <b>ROOT</b> super-admin reaches ADMIN and MODERATOR surfaces and an <b>ADMIN</b> reaches MODERATOR
     * ones — {@code ROLE_ROOT > ROLE_ADMIN > ROLE_MODERATOR}.
     *
     * <p>WHY (real RBAC bug surfaced by E2E): without a hierarchy, a holder of ROOT alone is DENIED
     * endpoints guarded by {@code hasRole('MODERATOR')} or {@code hasAnyRole('ADMIN','MODERATOR')}
     * (e.g. the moderation queue and the admin report queue), because Spring's {@code hasRole} matches
     * the literal authority. The hierarchy makes ROOT/ADMIN imply the lower staff roles platform-wide,
     * so endpoints need not enumerate every superior role.</p>
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("ROLE_ROOT > ROLE_ADMIN\nROLE_ADMIN > ROLE_MODERATOR");
    }

    /**
     * Wires {@link #roleHierarchy()} into method-security so {@code @PreAuthorize("hasRole(...)"} /
     * {@code hasAnyRole(...)} on controllers honour the hierarchy (deny-by-default is unchanged).
     */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }

    /**
     * Builds the CORS policy from the externalised allow-list.
     *
     * <p>WHY explicit origins + {@code allowCredentials=true} only together with named origins:
     * the spec forbids {@code *} with credentials, and so do we (PRD §18). If the allow-list is empty,
     * no cross-origin browser client is permitted (server-to-server/native clients are unaffected).</p>
     */
    private CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language",
                "Idempotency-Key", "If-Match"));
        config.setExposedHeaders(List.of("ETag"));
        // Only enable credentials when concrete origins are configured (never with a wildcard).
        config.setAllowCredentials(!corsProperties.allowedOrigins().isEmpty());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** Writes a localised {@link com.taarifu.common.api.dto.ApiResponse} for security-layer rejections. */
    private void writeEnvelope(jakarta.servlet.http.HttpServletResponse response,
                               ObjectMapper objectMapper,
                               ResponseFactory responseFactory,
                               ErrorCode errorCode) throws java.io.IOException {
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        // No field-level errors at the security filter layer; the cast binds to the field-errors
        // overload so data carries just the machine code (data.code) — same shape as the advice (ADR-0008).
        objectMapper.writeValue(response.getWriter(), responseFactory.error(errorCode, (List<ErrorDetail>) null));
    }
}
