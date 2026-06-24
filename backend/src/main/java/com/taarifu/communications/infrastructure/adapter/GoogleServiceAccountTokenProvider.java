package com.taarifu.communications.infrastructure.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Mints and caches a Google OAuth2 access token from a GCP <b>service-account JSON</b>, for the FCM HTTP v1
 * push adapter — the OAuth2 "JWT bearer" / server-to-server flow, done with two HTTPS calls and no Google
 * SDK (PRD §21 EI-5, DI1; ARCHITECTURE.md §7).
 *
 * <p>Responsibility: (1) load {@code client_email} + {@code private_key} from the service-account JSON at a
 * configured path; (2) build a short-lived RS256-signed assertion JWT (Nimbus JOSE — already on the
 * classpath); (3) exchange it at Google's token endpoint for an access token scoped to FCM; (4) cache the
 * token until shortly before it expires so the hot push path does not re-exchange on every send.</p>
 *
 * <p><b>Privacy / secrets (PRD §18, CLAUDE.md §12)</b>: the private key is read from the file the operator
 * mounts from a secret manager — it is <b>never</b> in source, never logged, and stays inside this
 * infrastructure helper. Only the resulting bearer (itself short-lived) is handed to the adapter.</p>
 *
 * <p><b>WHY a separate helper (not inline in the adapter)</b>: isolates the credential parsing + signing +
 * token-cache concern (SRP) so the {@code FcmHttpPushSender} stays a clean "build message → POST" adapter,
 * and so the token logic is testable in isolation. The class is constructed lazily inside the adapter only
 * when {@code push.provider=fcm}, so no credential file is touched unless FCM is actually selected.</p>
 */
public class GoogleServiceAccountTokenProvider {

    /** Google's OAuth2 token endpoint (the JWT-bearer exchange target). */
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    /** The OAuth2 grant type for a signed-JWT assertion (RFC 7523). */
    private static final String JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    /** FCM send scope the access token is minted for. */
    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    /** Assertion lifetime; Google permits up to 1h. */
    private static final Duration ASSERTION_TTL = Duration.ofMinutes(60);

    /** Refresh the cached token this long BEFORE it actually expires (avoids edge-of-expiry 401s). */
    private static final Duration REFRESH_SKEW = Duration.ofMinutes(2);

    private final String clientEmail;
    private final RSAPrivateKey privateKey;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    /** Cached bearer + the instant it must be refreshed by. Guarded by {@code this}. */
    private String cachedToken;
    private Instant refreshAfter = Instant.EPOCH;

    /**
     * Production constructor: reads + parses the service-account file at construction (fail-fast on a bad
     * file) and builds a timeout-bounded {@link RestClient} for the token exchange.
     *
     * @param credentialsFile path to the GCP service-account JSON (env-provided; never committed).
     * @param objectMapper    the shared Jackson mapper.
     * @param requestTimeout  per-request timeout for the token exchange.
     * @throws IllegalStateException if the file is missing/unreadable or lacks {@code client_email}/
     *                               {@code private_key} — a misconfiguration that must fail at startup.
     */
    public GoogleServiceAccountTokenProvider(String credentialsFile, ObjectMapper objectMapper,
                                             Duration requestTimeout) {
        this.objectMapper = objectMapper;
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        int timeoutMs = (int) requestTimeout.toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        try {
            JsonNode sa = objectMapper.readTree(Files.readString(Path.of(credentialsFile)));
            this.clientEmail = required(sa, "client_email", credentialsFile);
            this.privateKey = parsePrivateKey(required(sa, "private_key", credentialsFile));
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to load FCM service-account JSON at taarifu.communications.push.credentials-file"
                    + " (" + ex.getClass().getSimpleName() + ").", ex);
        }
    }

    /**
     * Test/seam constructor: supplies the parsed identity, mapper, and a mock-transport {@link RestClient}
     * directly, so token-exchange request/response handling is tested with no file and no network.
     *
     * @param clientEmail  the service-account email (assertion {@code iss}/{@code sub}).
     * @param privateKey   the RSA signing key.
     * @param objectMapper the Jackson mapper.
     * @param restClient   the (mock-transport) client.
     */
    GoogleServiceAccountTokenProvider(String clientEmail, RSAPrivateKey privateKey,
                                      ObjectMapper objectMapper, RestClient restClient) {
        this.clientEmail = clientEmail;
        this.privateKey = privateKey;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    /**
     * Returns a valid FCM access token, exchanging a freshly signed assertion only when the cache is
     * empty/near-expiry. Thread-safe (synchronised) — the push path may call this concurrently.
     *
     * @return the OAuth2 bearer (never {@code null}; throws on exchange failure so the adapter degrades).
     */
    public synchronized String accessToken() {
        if (cachedToken != null && Instant.now().isBefore(refreshAfter)) {
            return cachedToken;
        }
        TokenResponse response = exchange(buildAssertion());
        this.cachedToken = response.accessToken();
        // Refresh slightly before the real expiry to avoid using a token that lapses mid-flight.
        this.refreshAfter = Instant.now()
                .plusSeconds(Math.max(0, response.expiresInSeconds() - REFRESH_SKEW.toSeconds()));
        return cachedToken;
    }

    /** Builds and RS256-signs the assertion JWT. Package-visible so a test can assert its claims. */
    String buildAssertion() {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(clientEmail)
                .subject(clientEmail)
                .audience(TOKEN_URL)
                .claim("scope", FCM_SCOPE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ASSERTION_TTL)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((PrivateKey) privateKey));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign FCM assertion JWT.", ex);
        }
    }

    /** Posts the assertion to Google's token endpoint and parses the access token + TTL. */
    private TokenResponse exchange(String assertion) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", JWT_BEARER_GRANT);
        form.add("assertion", assertion);
        String body = restClient.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        try {
            JsonNode json = objectMapper.readTree(body);
            String token = json.path("access_token").asText(null);
            long expiresIn = json.path("expires_in").asLong(3600L);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Google token response carried no access_token.");
            }
            return new TokenResponse(token, expiresIn);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Google token response.", ex);
        }
    }

    /** Reads a required string field from the service-account JSON, or fails fast with a non-secret message. */
    private static String required(JsonNode sa, String field, String file) {
        String value = sa.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "FCM service-account JSON at " + file + " is missing the '" + field + "' field.");
        }
        return value;
    }

    /**
     * Parses a PKCS#8 PEM {@code private_key} (the standard form in a GCP service-account JSON) into an
     * {@link RSAPrivateKey}. Strips the PEM header/footer and Base64-decodes the DER body.
     */
    private static RSAPrivateKey parsePrivateKey(String pem) throws Exception {
        String der = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(der.getBytes(StandardCharsets.US_ASCII));
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    /**
     * Google's token-endpoint response, narrowed to what we use.
     *
     * @param accessToken      the OAuth2 bearer.
     * @param expiresInSeconds the token lifetime in seconds.
     */
    private record TokenResponse(String accessToken, long expiresInSeconds) {
    }

    /** @return the scopes this provider mints tokens for (diagnostics/test). */
    static List<String> scopes() {
        return List.of(FCM_SCOPE);
    }
}
