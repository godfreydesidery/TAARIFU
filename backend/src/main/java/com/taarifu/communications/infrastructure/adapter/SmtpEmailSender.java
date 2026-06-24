package com.taarifu.communications.infrastructure.adapter;

import com.taarifu.communications.domain.port.EmailSender;
import com.taarifu.communications.infrastructure.config.CommunicationsChannelProperties;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Production {@link EmailSender} adapter over <b>Spring Mail / SMTP</b> (PRD §21 EI-6, §18;
 * ARCHITECTURE.md §7). Selected by {@code taarifu.communications.email.provider=smtp}.
 *
 * <p>Responsibility: maps the provider-agnostic {@link EmailMessage} to a {@link MimeMessage} (UTF-8 so
 * Swahili diacritics survive — ADR-0010) and hands it to the auto-configured {@link JavaMailSender}, which
 * connects to the ESP over the {@code spring.mail.*} transport. The {@code From} (and optional
 * {@code Reply-To}) come from {@code taarifu.communications.email.*}; the address must be SPF/DKIM/DMARC
 * aligned <b>at the ESP</b> (deliverability is an ESP/DNS concern, not this adapter's). Bounce/complaint
 * events arrive asynchronously on a separate webhook (out of scope for this outbound adapter).</p>
 *
 * <p><b>Degradation (EI-6, "degrade, don't crash; never blocks signup")</b>: this adapter <b>never
 * throws</b> for a routine failure. A transport/SMTP error is caught and returned as
 * {@link EmailResult#failed(String)} with a <b>non-PII</b> reason, so the caller queues/retries and the
 * citizen path never blocks (signup completes without the email; the OTP email is a fallback, not a hard
 * dependency — R29). The transport timeout is configured via {@code spring.mail.properties.mail.smtp.*}.</p>
 *
 * <p><b>Privacy (PRD §18)</b>: the recipient address is sent to the ESP but <b>never logged</b> — only a
 * masked address, the purpose tag, and the body length. SMTP credentials live in
 * {@code spring.mail.username}/{@code spring.mail.password} (env, never source) and are never logged.</p>
 *
 * <p><b>WHY Spring Mail (no ESP SDK)</b>: SMTP is the universal ESP contract; an ESP-specific SDK would
 * lock us to one vendor and add a dependency. {@code JavaMailSender} is auto-configured by
 * {@code spring-boot-starter-mail} only when {@code spring.mail.host} is set, so an unconfigured prod
 * still boots (the logging default {@link LoggingEmailSenderStub} stays active until both
 * {@code email.provider=smtp} and the transport are configured). The adapter is unit-testable by injecting
 * a mock {@link JavaMailSender} and asserting the built {@link MimeMessage} with no real SMTP.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.communications.email.provider", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final CommunicationsChannelProperties.Email config;

    /**
     * @param mailSender the auto-configured Spring mail sender (transport from {@code spring.mail.*}).
     * @param properties the bound channel settings; the email group must carry a non-blank {@code from}
     *                   when this adapter is active.
     * @throws IllegalStateException if the {@code from} address is absent — booting an active SMTP adapter
     *                               without a sender is a misconfiguration that must fail fast.
     */
    public SmtpEmailSender(JavaMailSender mailSender, CommunicationsChannelProperties properties) {
        this.mailSender = mailSender;
        this.config = properties.email();
        if (config.from() == null) {
            throw new IllegalStateException(
                    "taarifu.communications.email.from must be set when email.provider=smtp "
                    + "(an SPF/DKIM/DMARC-aligned sender address).");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Builds a UTF-8 plain-text {@link MimeMessage} from the configured {@code From}, the recipient, and
     * the localised subject/body, then sends it. Returns {@code accepted} with a synthesised message id on
     * success, {@code failed(reason)} on any transport error — never throwing.</p>
     */
    @Override
    public EmailResult send(EmailMessage message) {
        try {
            MimeMessage mime = buildMime(message);
            mailSender.send(mime);
            // Redacted: never log the address, subject, or body (PRD §18).
            log.info("EMAIL sent: to={}, purpose={}, bodyLen={}, accepted",
                    mask(message.recipientEmail()), message.purpose(),
                    message.body() == null ? 0 : message.body().length());
            // SMTP gives no portable message id at submit; synthesise a stable one from the idempotency key.
            return EmailResult.accepted(messageId(message.idempotencyKey()));
        } catch (Exception ex) {
            // Degrade, don't crash (EI-6): the caller queues/retries; the citizen path never blocks. Reason
            // is the exception type only — never the address/subject/body, never a stack trace (PRD §18).
            log.warn("EMAIL send failed: to={}, purpose={}, reason={}",
                    mask(message.recipientEmail()), message.purpose(), ex.getClass().getSimpleName());
            return EmailResult.failed("EMAIL_SEND_FAILED");
        }
    }

    /**
     * Builds the MIME message. Package-visible so a unit test can assert the {@code From}/{@code To}/
     * subject/body/charset without a live transport.
     *
     * @param message the message to render.
     * @return the populated {@link MimeMessage}.
     * @throws Exception if address parsing or MIME assembly fails (treated as a routine failure by
     *                   {@link #send}).
     */
    MimeMessage buildMime(EmailMessage message) throws Exception {
        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
        helper.setFrom(fromAddress());
        helper.setTo(message.recipientEmail());
        helper.setSubject(message.subject() == null ? "" : message.subject());
        helper.setText(message.body() == null ? "" : message.body(), false);
        if (config.replyTo() != null) {
            helper.setReplyTo(config.replyTo());
        }
        return mime;
    }

    /** Builds the {@code From} with an optional display name; falls back to the bare address on encoding error. */
    private InternetAddress fromAddress() throws UnsupportedEncodingException, jakarta.mail.internet.AddressException {
        if (config.fromName() != null) {
            return new InternetAddress(config.from(), config.fromName(), StandardCharsets.UTF_8.name());
        }
        return new InternetAddress(config.from());
    }

    /** Synthesises a deterministic, non-PII message id from the idempotency key for audit correlation. */
    private static String messageId(String idempotencyKey) {
        return (idempotencyKey == null || idempotencyKey.isBlank())
                ? "smtp-" + UUID.randomUUID()
                : "smtp-" + idempotencyKey;
    }

    /** Masks an email to {@code a…masked@…} so logs carry no full address (PDPA). */
    private static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        return email.substring(0, 1) + "…masked@…";
    }
}
