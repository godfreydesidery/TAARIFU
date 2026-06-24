package com.taarifu.communications;

import com.taarifu.communications.domain.model.Notification;
import com.taarifu.communications.domain.model.NotificationPreference;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationStatus;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.port.EmailSender;
import com.taarifu.communications.domain.port.PushSender;
import com.taarifu.communications.domain.port.SmsGateway;
import com.taarifu.communications.application.service.NotificationDispatchService;
import com.taarifu.communications.domain.repository.NotificationPreferenceRepository;
import com.taarifu.communications.domain.repository.NotificationRepository;
import com.taarifu.identity.api.RecipientContactApi;
import com.taarifu.identity.api.dto.RecipientContact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationDispatchService} — the channel-selection, fallback, and idempotency
 * invariants of M5 (PRD §13, EI-3/5/6, DI4).
 *
 * <p>Responsibility: pins the load-bearing dispatch rules without a DB (Mockito only): FEED is always
 * retained; SMS is opt-out by default (cost); a disabled channel is suppressed unless the type is
 * always-on; PUSH with no device token falls back to SMS; quiet hours suppress interruptive channels but
 * never FEED; and a replayed dispatch never double-sends. Each test that would fail if the guard were
 * removed is the proof the PRD demands.</p>
 */
class NotificationDispatchServiceTest {

    private NotificationRepository notificationRepository;
    private NotificationPreferenceRepository preferenceRepository;
    private SmsGateway smsGateway;
    private PushSender pushSender;
    private EmailSender emailSender;
    private RecipientContactApi recipientContactApi;
    private FixedClock clock;
    private NotificationDispatchService service;

    private final UUID recipient = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();

    /** A usable, verified contact the default stub resolves for the recipient (raw — never asserted in logs). */
    private static final RecipientContact CONTACT =
            new RecipientContact("+255712345678", "mwananchi@example.tz");

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        preferenceRepository = mock(NotificationPreferenceRepository.class);
        smsGateway = mock(SmsGateway.class);
        pushSender = mock(PushSender.class);
        emailSender = mock(EmailSender.class);
        recipientContactApi = mock(RecipientContactApi.class);
        // Noon UTC == 15:00 EAT — outside any typical night quiet window.
        clock = new FixedClock(Instant.parse("2026-06-23T12:00:00Z"));
        service = new NotificationDispatchService(notificationRepository, preferenceRepository,
                smsGateway, pushSender, emailSender, recipientContactApi, clock);

        // Default: no preferences, no existing dispatch rows, save returns the argument.
        when(preferenceRepository.findByProfileId(any())).thenReturn(List.of());
        when(notificationRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(notificationRepository.existsByIdempotencyKey(anyString())).thenReturn(false);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(pushSender.send(any())).thenReturn(PushSender.PushResult.ok());
        when(smsGateway.send(any())).thenReturn(SmsGateway.SmsSendResult.accepted("ok"));
        when(emailSender.send(any())).thenReturn(EmailSender.EmailResult.accepted("ok"));
        // By default identity resolves a usable phone+email for the recipient.
        when(recipientContactApi.contactFor(any())).thenReturn(Optional.of(CONTACT));
    }

    @Test
    void feedIsAlwaysRetained_evenWhenOnlyFeedRequested() {
        List<Notification> sent = service.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Set.of(Channel.FEED), "ref", source, "T", "B");

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getChannel()).isEqualTo(Channel.FEED);
        // FEED is "delivered" by persistence alone — the item is never lost (EI-5).
        assertThat(sent.get(0).getStatus()).isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    void smsIsOptOutByDefault_whenNoPreferenceRow() {
        // Request FEED + SMS; with no preference, SMS defaults OFF (cost) — only FEED is dispatched.
        List<Notification> sent = service.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Set.of(Channel.FEED, Channel.SMS), "ref", source, "T", "B");

        assertThat(sent).extracting(Notification::getChannel).containsExactly(Channel.FEED);
        verify(smsGateway, never()).send(any());
    }

    @Test
    void smsIsSent_whenCitizenOptedIn_addressedToTheResolvedMsisdn() {
        when(preferenceRepository.findByProfileId(recipient)).thenReturn(List.of(
                NotificationPreference.of(recipient, NotificationType.NEW_ANNOUNCEMENT, Channel.SMS, true)));

        List<Notification> sent = service.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Set.of(Channel.FEED, Channel.SMS), "ref", source, "T", "B");

        assertThat(sent).extracting(Notification::getChannel)
                .containsExactlyInAnyOrder(Channel.FEED, Channel.SMS);
        // The gateway must receive the recipient's REAL E.164 (resolved via identity), never the profile id.
        ArgumentCaptor<SmsGateway.SmsMessage> msg = ArgumentCaptor.forClass(SmsGateway.SmsMessage.class);
        verify(smsGateway, times(1)).send(msg.capture());
        assertThat(msg.getValue().recipientE164()).isEqualTo(CONTACT.msisdn());
        assertThat(msg.getValue().recipientE164()).isNotEqualTo(recipient.toString());
    }

    @Test
    void emailIsSent_addressedToTheResolvedVerifiedAddress() {
        when(preferenceRepository.findByProfileId(recipient)).thenReturn(List.of(
                NotificationPreference.of(recipient, NotificationType.NEW_ANNOUNCEMENT, Channel.EMAIL, true)));

        service.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Set.of(Channel.FEED, Channel.EMAIL), "ref", source, "T", "B");

        // The email sender must receive the recipient's REAL address (resolved via identity).
        ArgumentCaptor<EmailSender.EmailMessage> msg = ArgumentCaptor.forClass(EmailSender.EmailMessage.class);
        verify(emailSender, times(1)).send(msg.capture());
        assertThat(msg.getValue().recipientEmail()).isEqualTo(CONTACT.email());
    }

    @Test
    void smsSkippedGracefully_whenRecipientHasNoMsisdn() {
        // An opted-in recipient whose contact cannot be resolved (no profile / anonymised) → empty.
        when(preferenceRepository.findByProfileId(recipient)).thenReturn(List.of(
                NotificationPreference.of(recipient, NotificationType.NEW_ANNOUNCEMENT, Channel.SMS, true)));
        when(recipientContactApi.contactFor(recipient)).thenReturn(Optional.empty());

        List<Notification> sent = service.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Set.of(Channel.FEED, Channel.SMS), "ref", source, "T", "B");

        // No gateway call (no number to dial), and the SMS row records the non-PII skip reason — never a crash.
        verify(smsGateway, never()).send(any());
        assertThat(sent).filteredOn(n -> n.getChannel() == Channel.SMS)
                .isNotEmpty()
                .allMatch(n -> n.getStatus() == NotificationStatus.FAILED);
        // FEED is still retained — one unreachable channel never loses the durable item (EI-5).
        assertThat(sent).filteredOn(n -> n.getChannel() == Channel.FEED)
                .allMatch(n -> n.getStatus() == NotificationStatus.DELIVERED);
    }

    @Test
    void emailSkippedGracefully_whenRecipientHasNoVerifiedEmail() {
        when(preferenceRepository.findByProfileId(recipient)).thenReturn(List.of(
                NotificationPreference.of(recipient, NotificationType.NEW_ANNOUNCEMENT, Channel.EMAIL, true)));
        // Identity resolves a phone but withholds the (unverified/absent) email → email is null.
        when(recipientContactApi.contactFor(recipient))
                .thenReturn(Optional.of(new RecipientContact("+255712345678", null)));

        List<Notification> sent = service.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Set.of(Channel.FEED, Channel.EMAIL), "ref", source, "T", "B");

        verify(emailSender, never()).send(any());
        assertThat(sent).filteredOn(n -> n.getChannel() == Channel.EMAIL)
                .isNotEmpty()
                .allMatch(n -> n.getStatus() == NotificationStatus.FAILED);
    }

    @Test
    void feedOnlyDispatch_doesNotResolveContact() {
        // A FEED-only dispatch needs no phone/email — identity must not be queried (no needless PII access).
        service.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Set.of(Channel.FEED), "ref", source, "T", "B");

        verify(recipientContactApi, never()).contactFor(any());
    }

    @Test
    void pushFallsBackToSms_whenNoDeviceTokenAndSmsAllowed() {
        when(pushSender.send(any())).thenReturn(PushSender.PushResult.noDeviceToken());
        // SMS must be permitted for the fallback to fire → opt the citizen in to SMS.
        when(preferenceRepository.findByProfileId(recipient)).thenReturn(List.of(
                NotificationPreference.of(recipient, NotificationType.NEW_ANNOUNCEMENT, Channel.SMS, true)));

        service.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Set.of(Channel.FEED, Channel.PUSH, Channel.SMS), "ref", source, "T", "B");

        // The fallback SMS row is sent in addition to the primary SMS row → SMS gateway hit at least once.
        verify(smsGateway, times(2)).send(any());
        verify(pushSender, times(1)).send(any());
    }

    @Test
    void pushNoToken_doesNotFallBack_whenSmsNotPermitted() {
        when(pushSender.send(any())).thenReturn(PushSender.PushResult.noDeviceToken());
        // No SMS opt-in → SMS not permitted → no fallback, push row is FAILED.
        List<Notification> sent = service.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Set.of(Channel.FEED, Channel.PUSH), "ref", source, "T", "B");

        verify(smsGateway, never()).send(any());
        assertThat(sent).filteredOn(n -> n.getChannel() == Channel.PUSH)
                .allMatch(n -> n.getStatus() == NotificationStatus.FAILED);
    }

    @Test
    void quietHoursSuppressPush_butNeverFeed() {
        // Quiet 14:00–16:00 EAT covers 15:00 (noon UTC). Opt in to PUSH so only quiet hours can suppress it.
        NotificationPreference pushPref =
                NotificationPreference.of(recipient, NotificationType.NEW_ANNOUNCEMENT, Channel.PUSH, true);
        pushPref.update(true, LocalTime.of(14, 0), LocalTime.of(16, 0), "sw");
        when(preferenceRepository.findByProfileId(recipient)).thenReturn(List.of(pushPref));

        List<Notification> sent = service.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Set.of(Channel.FEED, Channel.PUSH), "ref", source, "T", "B");

        assertThat(sent).extracting(Notification::getChannel).containsExactly(Channel.FEED);
        verify(pushSender, never()).send(any());
    }

    @Test
    void alwaysOnType_ignoresOptOut() {
        // A citizen tries to silence SYSTEM push — it is always-on, so PUSH is still dispatched.
        NotificationPreference off =
                NotificationPreference.of(recipient, NotificationType.SYSTEM, Channel.PUSH, false);
        when(preferenceRepository.findByProfileId(recipient)).thenReturn(List.of(off));

        List<Notification> sent = service.dispatch(recipient, NotificationType.SYSTEM,
                Set.of(Channel.PUSH), "ref", source, "T", "B");

        assertThat(sent).extracting(Notification::getChannel).contains(Channel.PUSH);
        verify(pushSender, times(1)).send(any());
    }

    @Test
    void replayedDispatch_neverDoubleSends() {
        // The idempotency key already maps to an existing row → no new row, no send (DI4).
        Notification existing = Notification.queue(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Channel.FEED, "ref", "k");
        when(notificationRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.of(existing));

        List<Notification> sent = service.dispatch(recipient, NotificationType.NEW_ANNOUNCEMENT,
                Set.of(Channel.FEED), "ref", source, "T", "B");

        assertThat(sent).containsExactly(existing);
        verify(notificationRepository, never()).save(any());
    }
}
