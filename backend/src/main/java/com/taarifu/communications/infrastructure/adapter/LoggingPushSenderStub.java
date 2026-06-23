package com.taarifu.communications.infrastructure.adapter;

import com.taarifu.communications.domain.port.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test {@link PushSender} stub — logs a redacted record, sends nothing (ARCHITECTURE §7 stub principle).
 *
 * <p>Responsibility: lets the notification dispatch flow run with <b>zero external calls</b> in
 * {@code dev}/{@code test}. On send it logs only a non-PII line ({@code recipient=…, purpose=push,
 * deepLink present?}) and always reports {@code accepted} — it never logs the title/body. It exercises
 * the real port contract (idempotency key, redaction discipline) so the FCM/APNs adapter is a drop-in.</p>
 *
 * <p>WHY {@code @Profile({"dev","test"})}: this stub must never be active in production, where a real
 * push provider is wired. There is no production code path that "reads back" a delivered push.</p>
 */
@Component
@Profile({"dev", "test"})
public class LoggingPushSenderStub implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSenderStub.class);

    /** {@inheritDoc} */
    @Override
    public PushResult send(PushMessage message) {
        // Redacted: never log title/body; the recipient is a UUID (non-PII) but kept terse.
        log.info("DEV PUSH accepted: recipient={}, hasDeepLink={}, key={}",
                message.recipientProfileId(),
                message.deepLinkRef() != null,
                message.idempotencyKey());
        return PushResult.ok();
    }
}
