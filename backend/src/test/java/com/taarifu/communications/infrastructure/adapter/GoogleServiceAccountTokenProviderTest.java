package com.taarifu.communications.infrastructure.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link GoogleServiceAccountTokenProvider} — proves the OAuth2 JWT-bearer assertion is
 * signed with the correct claims and the token-exchange request/response is handled, with a <b>locally
 * generated RSA key, no file, and no network</b> (CLAUDE.md §10).
 *
 * <p>Responsibility: the provider is built via its test seam (parsed identity + key + a mock-transport
 * {@link RestClient}). The tests assert the assertion JWT carries {@code iss}/{@code sub} = the
 * service-account email, {@code aud} = Google's token endpoint, and the FCM scope; that the exchange POSTs
 * a form to the token endpoint and surfaces the returned {@code access_token}; and that the token is
 * <b>cached</b> (a second call performs no second exchange).</p>
 */
class GoogleServiceAccountTokenProviderTest {

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String EMAIL = "svc@taarifu.iam.gserviceaccount.com";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RSAPrivateKey privateKey = generateKey();

    private static RSAPrivateKey generateKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            return (RSAPrivateKey) pair.getPrivate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void buildAssertion_signsJwtWithGoogleClaims() throws Exception {
        GoogleServiceAccountTokenProvider provider = new GoogleServiceAccountTokenProvider(
                EMAIL, privateKey, objectMapper, RestClient.builder().build());

        SignedJWT jwt = SignedJWT.parse(provider.buildAssertion());
        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        assertThat(claims.getIssuer()).isEqualTo(EMAIL);
        assertThat(claims.getSubject()).isEqualTo(EMAIL);
        assertThat(claims.getAudience()).containsExactly(TOKEN_URL);
        assertThat(claims.getStringClaim("scope")).contains("firebase.messaging");
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
    }

    @Test
    void accessToken_exchangesAssertion_andCachesResult() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Exactly ONE exchange is expected; the second accessToken() call must hit the cache, not re-POST.
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andRespond(withSuccess(
                        "{\"access_token\":\"ya29.fake\",\"expires_in\":3600,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));

        GoogleServiceAccountTokenProvider provider = new GoogleServiceAccountTokenProvider(
                EMAIL, privateKey, objectMapper, builder.build());

        assertThat(provider.accessToken()).isEqualTo("ya29.fake");
        // Second call is served from cache — no second request is registered, so verify() still passes.
        assertThat(provider.accessToken()).isEqualTo("ya29.fake");
        server.verify();
    }
}
