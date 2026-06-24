package com.taarifu.communications.infrastructure.adapter;

import com.taarifu.communications.domain.port.SmsGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link SmsGateway} — logs a <b>redacted</b> record, never the OTP, sends nothing
 * (AUTH-DESIGN §12, S-4). This is the <b>safe prod-bootable no-op</b> selected when no real SMS provider
 * is configured.
 *
 * <p>Responsibility: lets every environment — {@code dev}/{@code test} <i>and</i> a no-profile production
 * context with no aggregator configured — <b>boot and run with zero external calls</b>. On send it logs
 * only a <b>redacted</b> line ({@code to=+255…masked, purpose=…, len=…, accepted}) — the message body
 * (which carries the OTP code) and the raw recipient are <b>never</b> logged (S-4, PRD §18). It always
 * reports {@code accepted}.</p>
 *
 * <p><b>WHY {@code @ConditionalOnProperty(..., matchIfMissing = true)} (was {@code @Profile})</b>: the
 * three channel ports previously had only {@code @Profile({"dev","test"})} stubs, so a no-profile prod
 * context failed to start on the missing {@code SmsGateway} bean. This bean is now the
 * <b>match-if-missing default</b> for {@code taarifu.communications.sms.provider}: with no provider set it
 * is the one active bean (prod boots, safely degrading to log-only); the real {@code HttpSmsGateway} is
 * selected only by {@code provider=http}, and the two are mutually exclusive on the same property so
 * <b>exactly one {@link SmsGateway} bean exists in every environment</b>. Dev/test set no provider, so
 * their behaviour is unchanged.</p>
 *
 * <p>To support automated E2E that must complete an OTP flow without a real SMS, it retains the last
 * message body per recipient <b>in memory</b>, retrievable via {@link #lastBodyFor(String)}. This hook is
 * confined to the {@code dev}/{@code test} profiles at <b>runtime</b> (see {@link #devReadbackEnabled}):
 * in production it never retains a body, so there is no path that reads a delivered OTP back (S-4, PDPA).</p>
 *
 * <p>WHY a logging bean (not a bare no-op): the OTP/notification path must exercise the real port contract
 * (idempotency key, redacted logging discipline) so the aggregator adapter is a drop-in (ARCHITECTURE §7
 * stub principle).</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.communications.sms.provider", havingValue = "logging",
        matchIfMissing = true)
public class LoggingSmsGatewayStub implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsGatewayStub.class);

    /** Last message body per recipient — dev/test retrieval hook only; never persisted/logged. */
    private final Map<String, String> lastBodyByRecipient = new ConcurrentHashMap<>();

    /**
     * Whether the in-memory OTP read-back hook is active. {@code true} only under the {@code dev}/{@code
     * test} profiles — so a no-profile production deployment using this default bean NEVER retains a
     * delivered body in memory (S-4, PDPA). Resolved once at construction from the active profiles.
     */
    private final boolean devReadbackEnabled;

    /**
     * @param environment the Spring environment; consulted once to decide whether the dev/test OTP
     *                    read-back hook is active (active profiles include {@code dev} or {@code test}).
     */
    public LoggingSmsGatewayStub(Environment environment) {
        this.devReadbackEnabled = environment.acceptsProfiles(
                org.springframework.core.env.Profiles.of("dev", "test"));
    }

    /** {@inheritDoc} */
    @Override
    public SmsSendResult send(SmsMessage message) {
        if (devReadbackEnabled && message.recipientE164() != null) {
            lastBodyByRecipient.put(message.recipientE164(), message.body());
        }
        // Redacted: mask the recipient, never log the body (the OTP lives there — S-4).
        log.info("DEV SMS accepted: to={}, purpose={}, len={}",
                mask(message.recipientE164()),
                message.purpose(),
                message.body() == null ? 0 : message.body().length());
        return SmsSendResult.accepted("dev-stub");
    }

    /**
     * Dev/test-only retrieval of the last message body sent to a recipient (so E2E can read the OTP it
     * just triggered). Not present in production (the bean is profile-scoped).
     *
     * @param recipientE164 the recipient the message went to.
     * @return the last body, or empty if none sent.
     */
    public Optional<String> lastBodyFor(String recipientE164) {
        return Optional.ofNullable(lastBodyByRecipient.get(recipientE164));
    }

    /** Masks a phone to {@code +2557…masked} so logs carry no full MSISDN (S-4, PDPA). */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 5) {
            return "***";
        }
        return phone.substring(0, 5) + "…masked";
    }
}
