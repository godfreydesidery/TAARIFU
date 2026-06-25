package com.taarifu.ussd.infrastructure.adapter;

import com.taarifu.ussd.application.port.UssdSmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fallback {@link UssdSmsSender} adapter — logs a <b>redacted</b> record and never the body or the raw MSISDN
 * (S-4, PDPA), sending nothing, so the USSD flows complete with <b>zero external calls</b> (ARCHITECTURE §7
 * stub principle).
 *
 * <p>Responsibility: stand in for communications' SMS capability for the USSD confirmation/status sends when no
 * real SMS path is wired. It always succeeds and never throws — a confirmation SMS failure must not break the
 * dialogue (EI-3).</p>
 *
 * <p><b>WHY {@code @ConditionalOnProperty(name = "taarifu.ussd.sms.sender", havingValue = "logging")} (was the
 * unconditional default):</b> A3/ADR-0019 introduced the production {@link CommunicationsUssdSmsSender} that
 * delegates to communications' published {@code SmsSendApi} — that is now the <b>match-if-missing default</b>
 * for {@code taarifu.ussd.sms.sender}. This stub is selected only by an explicit {@code sender=logging}, so the
 * two adapters are mutually exclusive on the same property and <b>exactly one {@link UssdSmsSender} bean exists
 * in every environment</b>. By default the USSD path now sends through the one published port (which itself
 * degrades to communications' masked SMS stub when no aggregator is configured); set {@code sender=logging}
 * only to force the zero-external-call no-op directly in this module.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.ussd.sms.sender", havingValue = "logging")
public class LoggingUssdSmsSenderStub implements UssdSmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingUssdSmsSenderStub.class);

    /** {@inheritDoc} */
    @Override
    public void send(String recipientE164, String body, String idempotencyKey) {
        // Zero external calls: log only a redacted line; never the body or the raw MSISDN (S-4).
        log.info("DEV USSD SMS accepted (logging stub): to={}, len={}, key={}",
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
