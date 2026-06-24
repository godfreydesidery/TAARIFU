package com.taarifu.communications.infrastructure.adapter;

import com.taarifu.communications.domain.port.EmailSender;
import com.taarifu.communications.infrastructure.config.CommunicationsChannelProperties;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Unit tests for {@link SmtpEmailSender} — proves the MIME message is built from the configured envelope
 * and the transport outcome is mapped to the port result, with <b>no real SMTP</b> (CLAUDE.md §10).
 *
 * <p>Responsibility: a spied {@link JavaMailSenderImpl} (used only to {@code createMimeMessage}, never to
 * connect) lets the test capture the {@link MimeMessage} the adapter sends and assert the load-bearing
 * contract — the {@code From} is the configured sender, the {@code To} is the recipient, the subject/body
 * are set, and a UTF-8 charset is used so Swahili survives (ADR-0010). A transport exception degrades to
 * {@code failed} <b>without throwing</b> (EI-6), and a blank {@code from} fails fast.</p>
 */
class SmtpEmailSenderTest {

    private CommunicationsChannelProperties props(String from, String fromName, String replyTo) {
        CommunicationsChannelProperties.Email email = new CommunicationsChannelProperties.Email(
                "smtp", from, fromName, replyTo);
        return new CommunicationsChannelProperties(null, null, email);
    }

    /** A real-but-offline mail sender: it can mint a MimeMessage; we never call connect/send for real. */
    private JavaMailSenderImpl offlineSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setJavaMailProperties(new Properties());
        return sender;
    }

    @Test
    void send_buildsMimeFromConfiguredEnvelope_andReportsAccepted() throws Exception {
        JavaMailSenderImpl sender = spy(offlineSender());
        // Capture the message; do nothing on the actual transport send (no SMTP connection).
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        org.mockito.Mockito.doNothing().when(sender).send(captor.capture());

        SmtpEmailSender adapter = new SmtpEmailSender(sender, props("noreply@taarifu.go.tz", "Taarifu", null));
        EmailSender.EmailResult result = adapter.send(new EmailSender.EmailMessage(
                "joseph@example.com", "Karibu Taarifu", "Akaunti yako imeundwa.", "SIGNUP", "idem-1"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("smtp-idem-1");

        MimeMessage sent = captor.getValue();
        assertThat(sent.getFrom()[0].toString()).contains("noreply@taarifu.go.tz");
        assertThat(sent.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("joseph@example.com");
        assertThat(sent.getSubject()).isEqualTo("Karibu Taarifu");
        // The body round-trips intact — the UTF-8 helper preserves the (Swahili) text verbatim (ADR-0010).
        assertThat(sent.getContent().toString()).isEqualTo("Akaunti yako imeundwa.");
    }

    @Test
    void send_preservesSwahiliDiacritics_underUtf8() throws Exception {
        JavaMailSenderImpl sender = spy(offlineSender());
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        org.mockito.Mockito.doNothing().when(sender).send(captor.capture());

        SmtpEmailSender adapter = new SmtpEmailSender(sender, props("noreply@taarifu.go.tz", null, null));
        // Diacritics that force a non-ASCII charset; UTF-8 (ADR-0010) must preserve them.
        adapter.send(new EmailSender.EmailMessage("a@b.com", "Arifa", "Tahadhari: chagua “ndiyo”",
                "ALERT", "idem-sw"));

        MimeMessage sent = captor.getValue();
        sent.saveChanges();
        assertThat(sent.getContent().toString()).isEqualTo("Tahadhari: chagua “ndiyo”");
        // The transfer encoding is set so non-ASCII survives the wire (not bare 7bit).
        assertThat(sent.getContentType().toLowerCase()).contains("utf-8");
    }

    @Test
    void send_onTransportFailure_degradesToFailed_withoutThrowing() {
        JavaMailSenderImpl sender = spy(offlineSender());
        doThrow(new MailSendException("smtp down")).when(sender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));

        SmtpEmailSender adapter = new SmtpEmailSender(sender, props("noreply@taarifu.go.tz", null, null));
        EmailSender.EmailResult result = adapter.send(new EmailSender.EmailMessage(
                "joseph@example.com", "subj", "body", "DIGEST", "idem-2"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).isEqualTo("EMAIL_SEND_FAILED");
        // Reason carries no PII.
        assertThat(result.reason()).doesNotContain("joseph");
    }

    @Test
    void send_setsReplyTo_whenConfigured() throws Exception {
        JavaMailSenderImpl sender = spy(offlineSender());
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        org.mockito.Mockito.doNothing().when(sender).send(captor.capture());

        SmtpEmailSender adapter = new SmtpEmailSender(
                sender, props("noreply@taarifu.go.tz", null, "help@taarifu.go.tz"));
        adapter.send(new EmailSender.EmailMessage("a@b.com", "s", "b", "STAFF", "idem-3"));

        assertThat(captor.getValue().getReplyTo()[0].toString()).contains("help@taarifu.go.tz");
    }

    @Test
    void construction_withBlankFrom_failsFast() {
        var sender = mock(org.springframework.mail.javamail.JavaMailSender.class);
        CommunicationsChannelProperties props = props("  ", null, null);
        assertThatThrownBy(() -> new SmtpEmailSender(sender, props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email.from");
    }
}
