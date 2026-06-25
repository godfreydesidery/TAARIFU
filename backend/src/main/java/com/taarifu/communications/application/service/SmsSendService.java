package com.taarifu.communications.application.service;

import com.taarifu.communications.api.SmsSendApi;
import com.taarifu.communications.domain.port.SmsGateway;
import org.springframework.stereotype.Service;

/**
 * Communications' implementation of the published {@link SmsSendApi} — the synchronous
 * {@code ussd → communications} "send this SMS" seam (ADR-0013 §1; ADR-0019; A3; PRD §13/§14, EI-3).
 *
 * <p>Responsibility: bridge the published cross-module SMS-send port to communications' internal
 * {@link SmsGateway} domain port (which the real least-cost/DLR aggregator adapter or the prod-safe
 * {@code LoggingSmsGatewayStub} backs). It maps the public {@link SmsSendApi.SmsSendCommand} to the internal
 * {@link SmsGateway.SmsMessage} and the internal {@link SmsGateway.SmsSendResult} back to the public
 * {@link SmsSendApi.SmsSendResult} — so the aggregator's quirks and the recipient-masking discipline stay
 * owned inside this module and a sibling never touches {@code domain.port} (boundary discipline,
 * ARCHITECTURE §3.2).</p>
 *
 * <p>This service holds <b>no logic</b> beyond the record mapping — it is the published façade, not a second
 * delivery path. It is <b>not</b> {@code @Transactional}: an outbound send is a side-effecting I/O call with no
 * DB write of its own, and the gateway is fail-soft (it returns a result, never throws on a routine failure),
 * so there is nothing to roll back and nothing a transaction would protect (EI-3).</p>
 *
 * <p><b>🔒 PII (S-4, PDPA):</b> the raw E.164 recipient and the body are passed <b>straight</b> to the masking
 * gateway and are <b>never logged, persisted, or placed in an event</b> here. <b>Fence (D18):</b> no token
 * collaborator exists on this path.</p>
 */
@Service
public class SmsSendService implements SmsSendApi {

    private final SmsGateway smsGateway;

    /**
     * @param smsGateway communications' internal SMS delivery port (the masking aggregator/stub adapter).
     */
    public SmsSendService(SmsGateway smsGateway) {
        this.smsGateway = smsGateway;
    }

    /** {@inheritDoc} */
    @Override
    public SmsSendResult send(SmsSendCommand command) {
        // Map the public command → internal message (the raw recipient/body cross only into the masking
        // gateway), delegate, then map the internal result → public result. No PII is logged here (S-4).
        SmsGateway.SmsSendResult result = smsGateway.send(new SmsGateway.SmsMessage(
                command.recipientE164(), command.body(), command.purpose(), command.idempotencyKey()));
        return result.accepted()
                ? SmsSendResult.accepted(result.providerMessageId())
                : SmsSendResult.failed(result.reason());
    }
}
