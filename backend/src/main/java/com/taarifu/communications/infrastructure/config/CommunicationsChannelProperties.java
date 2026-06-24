package com.taarifu.communications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * Externalised, <b>non-secret</b> connection settings for the communications channel adapters
 * (SMS / push / email), bound from {@code taarifu.communications.*} (PRD §21 EI-3/5/6, §18;
 * ARCHITECTURE.md §7).
 *
 * <p>Responsibility: carries the per-channel <i>endpoints</i> and <i>tuning</i> the real adapters need —
 * the SMS aggregator HTTPS submit URL + sender-id, the FCM project id + token endpoint, and the email
 * "from"/reply-to. It holds the <b>provider selector</b> per channel ({@code sms.provider},
 * {@code push.provider}, {@code email.provider}) that {@code @ConditionalOnProperty} keys off so exactly
 * one adapter bean per port is active in every environment.</p>
 *
 * <p><b>WHY no credentials live here</b> (CLAUDE.md §12, PRD §18): the SMS API key, the FCM
 * service-account private key, and the SMTP password are <b>never</b> bound into source or a config
 * record. They are resolved at runtime from their own env placeholders — the SMS adapter reads
 * {@code taarifu.communications.sms.api-key} (an env var the operator sets), the FCM adapter reads the
 * service-account JSON from a file path or env, and SMTP credentials come from Spring's
 * {@code spring.mail.username}/{@code spring.mail.password}. This record only holds <i>where</i> to talk
 * and which provider to use, all of which are environment placeholders in {@code application.yml}.</p>
 *
 * <p>WHY one record with nested groups (not three): keeps the whole channel configuration surface in one
 * documented place (KISS, CLAUDE.md §8) and lets each adapter inject only the slice it needs, while the
 * single {@code @EnableConfigurationProperties} keeps wiring localised to this module.</p>
 *
 * @param sms   SMS aggregator settings (EI-3).
 * @param push  FCM push settings (EI-5).
 * @param email email/ESP settings (EI-6).
 */
@ConfigurationProperties(prefix = "taarifu.communications")
public record CommunicationsChannelProperties(
        @NestedConfigurationProperty Sms sms,
        @NestedConfigurationProperty Push push,
        @NestedConfigurationProperty Email email
) {

    /** Applies safe empty-but-non-null sub-records so an adapter never NPEs on an unset group. */
    public CommunicationsChannelProperties {
        if (sms == null) {
            sms = new Sms(null, null, null, null, null, null);
        }
        if (push == null) {
            push = new Push(null, null, null, null);
        }
        if (email == null) {
            email = new Email(null, null, null, null);
        }
    }

    /**
     * SMS aggregator connection + behaviour (EI-3). Maps to a generic HTTPS-submit aggregator — the
     * adapter POSTs {@code to/from/text} as form/JSON and treats a 2xx as accepted; the per-message
     * delivery receipt arrives later on a DLR webhook (out of scope for the outbound adapter).
     *
     * @param provider    the active SMS adapter selector: {@code http} (the HTTP aggregator adapter) or
     *                    {@code logging} (the safe default). Unset → {@code logging} (matchIfMissing).
     * @param submitUrl   the aggregator's HTTPS submit endpoint; required when {@code provider=http}.
     * @param senderId    the procured alphanumeric sender-id / shortcode (D-Q7, TCRA-registered) the
     *                    aggregator sends from; never PII.
     * @param apiKey      the aggregator API key/token — env-provided, NEVER committed (PRD §18). Sent as a
     *                    bearer/header per the aggregator's scheme.
     * @param authHeader  the HTTP header name the {@code apiKey} is sent in (default {@code Authorization}),
     *                    so the same adapter fits aggregators that use {@code X-API-Key} etc.
     * @param requestTimeout per-request timeout so a slow aggregator never piles up threads (EI-3
     *                    degradation; ARCHITECTURE §7). Default 5s.
     */
    public record Sms(
            String provider,
            String submitUrl,
            String senderId,
            String apiKey,
            String authHeader,
            Duration requestTimeout
    ) {
        /** Normalises blank placeholders to {@code null} and applies safe defaults. */
        public Sms {
            provider = blankToNull(provider);
            submitUrl = blankToNull(submitUrl);
            senderId = blankToNull(senderId);
            apiKey = blankToNull(apiKey);
            authHeader = blankToNull(authHeader);
            if (authHeader == null) {
                authHeader = "Authorization";
            }
            if (requestTimeout == null) {
                requestTimeout = Duration.ofSeconds(5);
            }
        }
    }

    /**
     * FCM HTTP v1 push connection (EI-5). The adapter mints a service-account-signed JWT, exchanges it for
     * a short-lived OAuth2 bearer at Google's token endpoint, and POSTs the message to the FCM v1 send
     * endpoint — a <b>thin HTTP adapter</b>, no firebase-admin SDK (PRD §21 DI1/EI-5).
     *
     * @param provider          the active push adapter selector: {@code fcm} or {@code logging} (default,
     *                          matchIfMissing).
     * @param projectId         the Firebase/GCP project id; the v1 send URL is derived from it. Required
     *                          when {@code provider=fcm}.
     * @param credentialsFile   filesystem path to the GCP <b>service-account JSON</b> (client_email +
     *                          private_key). Env-provided path, NEVER the key in source (PRD §18). Mounted
     *                          from a secret manager in prod.
     * @param requestTimeout    per-request timeout for token-exchange + send. Default 5s.
     */
    public record Push(
            String provider,
            String projectId,
            String credentialsFile,
            Duration requestTimeout
    ) {
        /** Normalises blank placeholders to {@code null} and applies safe defaults. */
        public Push {
            provider = blankToNull(provider);
            projectId = blankToNull(projectId);
            credentialsFile = blankToNull(credentialsFile);
            if (requestTimeout == null) {
                requestTimeout = Duration.ofSeconds(5);
            }
        }
    }

    /**
     * Email/ESP envelope settings (EI-6). Transport (host/port/user/pass/TLS) is Spring's
     * {@code spring.mail.*} — auto-configured {@code JavaMailSender}; this record only carries the
     * civic-facing envelope fields.
     *
     * @param provider  the active email adapter selector: {@code smtp} or {@code logging} (default,
     *                  matchIfMissing).
     * @param from      the {@code From} address (must be SPF/DKIM/DMARC-aligned at the ESP); required when
     *                  {@code provider=smtp}.
     * @param fromName  optional display name for the {@code From} (e.g. "Taarifu").
     * @param replyTo   optional {@code Reply-To} address, or {@code null}.
     */
    public record Email(
            String provider,
            String from,
            String fromName,
            String replyTo
    ) {
        /** Normalises blank placeholders to {@code null}. */
        public Email {
            provider = blankToNull(provider);
            from = blankToNull(from);
            fromName = blankToNull(fromName);
            replyTo = blankToNull(replyTo);
        }
    }

    /** @return {@code null} for a {@code null}/blank string, else the trimmed value. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
