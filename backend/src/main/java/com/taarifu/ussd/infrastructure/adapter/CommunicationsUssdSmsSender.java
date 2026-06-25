package com.taarifu.ussd.infrastructure.adapter;

import com.taarifu.communications.api.SmsSendApi;
import com.taarifu.ussd.application.port.UssdSmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production {@link UssdSmsSender} adapter — delegates to communications' published {@link SmsSendApi}
 * (the sanctioned synchronous {@code ussd → communications} contract, ADR-0013 §1; ADR-0019; A3).
 *
 * <p>Responsibility: bind the USSD module's consumer-owned SMS seam to communications' real SMS-send command
 * port, so the feature-phone <b>ticket-confirmation SMS</b> (UC-D02) and status replies really send through the
 * one owned delivery path (the least-cost/DLR aggregator, or communications' prod-safe masked stub when no
 * aggregator is configured) — no logged no-op any more. This closes the prior {@code // TODO(wiring)} on the
 * USSD SMS path.</p>
 *
 * <p>This adapter holds <b>no logic</b>: it maps the USSD send into the published command and delegates by
 * value only (a raw E.164 + body), never importing communications' internal {@code domain.port.SmsGateway}
 * (ADR-0013 §1). The published port is fail-soft (it returns a result, never throws on a routine failure), so
 * this adapter never throws either — a confirmation-SMS failure must not break the dialogue (EI-3); it logs a
 * <b>redacted</b> outcome only (S-4, never the raw MSISDN or body). No token is read on this path (the
 * civic-integrity fence, D18, §23.5) — neither {@link SmsSendApi} nor this adapter has any token collaborator.</p>
 *
 * <p><b>WHY {@code @ConditionalOnProperty(..., matchIfMissing = true)}:</b> this is the default
 * {@link UssdSmsSender} in every environment; the {@link LoggingUssdSmsSenderStub} is selected only by an
 * explicit {@code taarifu.ussd.sms.sender=logging}, so exactly one bean exists (mirrors the
 * {@code LoggingSmsGatewayStub} prod-boot pattern). The delegate {@code SmsSendApi} itself degrades to a masked
 * logging stub when no real aggregator is configured, so this default still boots and runs with zero external
 * calls out of the box.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.ussd.sms.sender", havingValue = "communications",
        matchIfMissing = true)
public class CommunicationsUssdSmsSender implements UssdSmsSender {

    private static final Logger log = LoggerFactory.getLogger(CommunicationsUssdSmsSender.class);

    /** Non-PII purpose tag for the USSD ticket/status SMS (metrics + idempotency on the gateway). */
    private static final String PURPOSE = "USSD_TICKET";

    private final SmsSendApi smsSendApi;

    /**
     * @param smsSendApi communications' published SMS-send command port.
     */
    public CommunicationsUssdSmsSender(SmsSendApi smsSendApi) {
        this.smsSendApi = smsSendApi;
    }

    /** {@inheritDoc} */
    @Override
    public void send(String recipientE164, String body, String idempotencyKey) {
        // Delegate to the published port (raw recipient/body cross only into the masking gateway behind it).
        // Never throw on a routine failure — the END line already carries the ticket code (EI-3).
        SmsSendApi.SmsSendResult result = smsSendApi.send(
                new SmsSendApi.SmsSendCommand(recipientE164, body, PURPOSE, idempotencyKey));
        if (!result.accepted()) {
            // Redacted outcome only — never the raw MSISDN or body (S-4). The aggregator queues/retries.
            log.warn("USSD confirmation SMS not accepted: to={}, key={}, reason={}",
                    mask(recipientE164), idempotencyKey, result.reason());
        }
    }

    /** Masks a phone to {@code +2557…masked} so logs carry no full MSISDN (S-4, PDPA). */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 5) {
            return "***";
        }
        return phone.substring(0, 5) + "…masked";
    }
}
