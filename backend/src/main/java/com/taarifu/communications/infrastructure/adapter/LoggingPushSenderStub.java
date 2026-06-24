package com.taarifu.communications.infrastructure.adapter;

import com.taarifu.communications.domain.port.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link PushSender} — logs a redacted record, sends nothing (ARCHITECTURE §7 stub principle).
 * This is the <b>safe prod-bootable no-op</b> selected when no real push provider is configured.
 *
 * <p>Responsibility: lets every environment — {@code dev}/{@code test} <i>and</i> a no-profile production
 * context with no FCM configured — <b>boot and run with zero external calls</b>. On send it logs only a
 * non-PII line ({@code recipient=…, hasDeepLink=…, key=…}) and reports {@code accepted} — it never logs
 * the title/body. It exercises the real port contract (idempotency key, redaction discipline) so the FCM
 * adapter is a drop-in.</p>
 *
 * <p><b>WHY {@code @ConditionalOnProperty(..., matchIfMissing = true)} (was {@code @Profile})</b>: this is
 * now the <b>match-if-missing default</b> for {@code taarifu.communications.push.provider}, so a no-profile
 * prod context with no FCM credentials still resolves a single {@link PushSender} bean and boots. The real
 * {@code FcmHttpPushSender} is selected only by {@code provider=fcm}; the two are mutually exclusive on the
 * same property, so <b>exactly one {@link PushSender} bean exists in every environment</b>. Dev/test set no
 * provider, so their behaviour is unchanged.</p>
 *
 * <p>Note this default reports {@code accepted}, not {@code noDeviceToken} — the dispatcher therefore does
 * NOT spuriously cascade every notification to the SMS fallback (and incur cost) when push is simply
 * unconfigured; FEED is always retained regardless (EI-5).</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.communications.push.provider", havingValue = "logging",
        matchIfMissing = true)
public class LoggingPushSenderStub implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSenderStub.class);

    /** {@inheritDoc} */
    @Override
    public PushResult send(PushMessage message) {
        // Redacted: never log title/body; the recipient is a UUID (non-PII) but kept terse.
        log.info("PUSH (logging default) accepted: recipient={}, hasDeepLink={}, key={}",
                message.recipientProfileId(),
                message.deepLinkRef() != null,
                message.idempotencyKey());
        return PushResult.ok();
    }
}
