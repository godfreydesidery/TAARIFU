package com.taarifu.ussd.infrastructure.adapter;

import com.taarifu.ussd.application.port.UssdSmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link UssdSmsSender} adapter — logs a <b>redacted</b> record and never the body or the raw MSISDN
 * (S-4, PDPA), so the USSD flows complete with <b>zero external calls</b> (ARCHITECTURE §7 stub principle).
 *
 * <p>Responsibility: stand in for communications' SMS capability for the USSD confirmation/status sends. It
 * always succeeds and never throws — a confirmation SMS failure must not break the dialogue (EI-3). The
 * production adapter delegates to communications' published SMS port once available ({@code // TODO(wiring)};
 * see CENTRAL INTEGRATION NEEDS).</p>
 */
@Component
public class LoggingUssdSmsSenderStub implements UssdSmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingUssdSmsSenderStub.class);

    /** {@inheritDoc} */
    @Override
    public void send(String recipientE164, String body, String idempotencyKey) {
        // TODO(wiring): delegate to communications' published SMS port (least-cost/DLR aggregator) once it
        // is republished in com.taarifu.communications.api (the domain.port SmsGateway is internal — ADR-0013).
        log.info("DEV USSD SMS accepted: to={}, len={}, key={}",
                mask(recipientE164),
                body == null ? 0 : body.length(),
                idempotencyKey);
    }

    /** Masks a phone to {@code +2557…masked} so logs carry no full MSISDN (S-4, PDPA). */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 5) {
            return "***";
        }
        return phone.substring(0, 5) + "…masked";
    }
}
