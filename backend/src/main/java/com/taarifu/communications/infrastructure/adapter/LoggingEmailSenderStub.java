package com.taarifu.communications.infrastructure.adapter;

import com.taarifu.communications.domain.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmailSender} — logs a redacted record, sends nothing (ARCHITECTURE §7 stub principle).
 * This is the <b>safe prod-bootable no-op</b> selected when no real ESP is configured.
 *
 * <p>Responsibility: lets every environment — {@code dev}/{@code test} <i>and</i> a no-profile production
 * context with no SMTP configured — <b>boot and run with zero external calls</b>. On send it logs only a
 * non-PII line ({@code to=…masked, purpose=…, bodyLen=…}) — never the subject/body and never the raw
 * address — and reports {@code accepted}. It exercises the real port contract so the SMTP adapter is a
 * drop-in.</p>
 *
 * <p><b>WHY {@code @ConditionalOnProperty(..., matchIfMissing = true)} (was {@code @Profile})</b>: this is
 * now the <b>match-if-missing default</b> for {@code taarifu.communications.email.provider}, so a
 * no-profile prod context with no ESP still resolves a single {@link EmailSender} bean and boots. The real
 * {@code SmtpEmailSender} is selected only by {@code provider=smtp}; the two are mutually exclusive on the
 * same property, so <b>exactly one {@link EmailSender} bean exists in every environment</b>. Dev/test set
 * no provider, so their behaviour is unchanged.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.communications.email.provider", havingValue = "logging",
        matchIfMissing = true)
public class LoggingEmailSenderStub implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSenderStub.class);

    /** {@inheritDoc} */
    @Override
    public EmailResult send(EmailMessage message) {
        // Redacted: mask the address, never log subject/body (PRD §18).
        log.info("DEV EMAIL accepted: to={}, purpose={}, bodyLen={}",
                mask(message.recipientEmail()),
                message.purpose(),
                message.body() == null ? 0 : message.body().length());
        return EmailResult.accepted("dev-stub");
    }

    /** Masks an email to {@code a…masked@…} so logs carry no full address (PDPA). */
    private static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, Math.min(1, at)) : email.substring(0, 1);
        return local + "…masked@…";
    }
}
