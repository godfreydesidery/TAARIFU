package com.taarifu.communications.infrastructure.adapter;

import com.taarifu.communications.domain.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test {@link EmailSender} stub — logs a redacted record, sends nothing (ARCHITECTURE §7 stub principle).
 *
 * <p>Responsibility: lets the notification dispatch flow run with <b>zero external calls</b> in
 * {@code dev}/{@code test}. On send it logs only a non-PII line ({@code to=…masked, purpose=…, len=…})
 * — never the subject/body and never the raw address — and always reports {@code accepted}. It exercises
 * the real port contract so the ESP adapter is a drop-in.</p>
 *
 * <p>WHY {@code @Profile({"dev","test"})}: this stub must never be active in production, where a real ESP
 * is wired.</p>
 */
@Component
@Profile({"dev", "test"})
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
