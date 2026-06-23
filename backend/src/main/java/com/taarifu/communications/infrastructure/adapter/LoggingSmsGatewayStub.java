package com.taarifu.communications.infrastructure.adapter;

import com.taarifu.communications.domain.port.SmsGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev/test {@link SmsGateway} stub — logs a <b>redacted</b> record, never the OTP (AUTH-DESIGN §12, S-4).
 *
 * <p>Responsibility: lets the auth flows run with <b>zero external calls</b> in {@code dev}/{@code test}.
 * On send it logs only a <b>redacted</b> line ({@code to=+255…masked, purpose=…, len=…, accepted}) — the
 * message body (which carries the OTP code) and the raw recipient are <b>never</b> logged (S-4, PRD §18).
 * It always reports {@code accepted}.</p>
 *
 * <p>To support automated E2E that must complete an OTP flow without a real SMS, it retains the last
 * message body per recipient <b>in memory</b>, retrievable via {@link #lastBodyFor(String)}. This hook is
 * deliberately confined to the dev/test profiles ({@code @Profile({"dev","test"})}) so it can never be
 * active in production — there is no production code path that reads a delivered OTP back.</p>
 *
 * <p>WHY a stub bean (not a no-op): the OTP path must exercise the real port contract (idempotency key,
 * redacted logging discipline) so the later aggregator adapter is a drop-in (ARCHITECTURE §7 stub
 * principle).</p>
 */
@Component
@Profile({"dev", "test"})
public class LoggingSmsGatewayStub implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsGatewayStub.class);

    /** Last message body per recipient — dev/test retrieval hook only; never persisted/logged. */
    private final Map<String, String> lastBodyByRecipient = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public SmsSendResult send(SmsMessage message) {
        if (message.recipientE164() != null) {
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
