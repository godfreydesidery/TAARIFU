package com.taarifu.ussd;

import com.taarifu.ussd.application.port.UssdSubscriptionPort;
import com.taarifu.ussd.application.service.UssdAlertService;
import com.taarifu.ussd.domain.model.UssdAlertSubscription;
import com.taarifu.ussd.domain.repository.UssdAlertSubscriptionRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UssdAlertService} — the my-area-alert capture + (config-gated) forward to
 * communications (A3, ADR-0019).
 *
 * <p>Asserts (1) the durable local intent is always written and a repeat is an idempotent no-op; (2) with
 * forwarding OFF (the default — the account↔profile grain CENTRAL NEED) the published port is NEVER called and
 * the row stays unforwarded — the safe degrade; (3) with forwarding ON the port is called and the row is marked
 * forwarded; (4) a forwarding failure never loses the local intent (fail-soft, EI-3).</p>
 */
class UssdAlertServiceTest {

    private final UssdAlertSubscriptionRepository repo = mock(UssdAlertSubscriptionRepository.class);
    private final UssdSubscriptionPort port = mock(UssdSubscriptionPort.class);

    private final UUID user = UUID.randomUUID();
    private final UUID ward = UUID.randomUUID();

    /** Default (forwarding OFF): the intent is captured locally and the published port is never touched. */
    @Test
    void subscribe_forwardingDisabled_capturesLocally_neverForwards() {
        when(repo.findByUserPublicIdAndWardId(user, ward)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UssdAlertService service = new UssdAlertService(repo, port, false);

        boolean created = service.subscribeArea(user, ward);

        assertThat(created).isTrue();
        verify(repo).save(any(UssdAlertSubscription.class));
        verify(port, never()).subscribeArea(any(), any());
    }

    /** A repeat subscription is an idempotent no-op (no new row, no forward). */
    @Test
    void subscribe_existing_isNoOp() {
        when(repo.findByUserPublicIdAndWardId(user, ward))
                .thenReturn(Optional.of(UssdAlertSubscription.of(user, ward)));
        UssdAlertService service = new UssdAlertService(repo, port, true);

        boolean created = service.subscribeArea(user, ward);

        assertThat(created).isFalse();
        verify(repo, never()).save(any());
        verify(port, never()).subscribeArea(any(), any());
    }

    /** Forwarding ON: the published port is called and the captured row is marked forwarded. */
    @Test
    void subscribe_forwardingEnabled_forwards_andMarksForwarded() {
        when(repo.findByUserPublicIdAndWardId(user, ward)).thenReturn(Optional.empty());
        UssdAlertSubscription saved = UssdAlertSubscription.of(user, ward);
        when(repo.save(any())).thenReturn(saved);
        when(port.subscribeArea(eq(user), eq(ward))).thenReturn(UUID.randomUUID());
        UssdAlertService service = new UssdAlertService(repo, port, true);

        service.subscribeArea(user, ward);

        verify(port).subscribeArea(user, ward);
        assertThat(saved.isForwarded()).isTrue();
    }

    /** Forwarding ON but the port throws: the local intent survives (fail-soft), the row stays unforwarded. */
    @Test
    void subscribe_forwardingFails_keepsLocalIntent() {
        when(repo.findByUserPublicIdAndWardId(user, ward)).thenReturn(Optional.empty());
        UssdAlertSubscription saved = UssdAlertSubscription.of(user, ward);
        when(repo.save(any())).thenReturn(saved);
        when(port.subscribeArea(any(), any())).thenThrow(new RuntimeException("comms down"));
        UssdAlertService service = new UssdAlertService(repo, port, true);

        boolean created = service.subscribeArea(user, ward);

        assertThat(created).isTrue();
        assertThat(saved.isForwarded()).isFalse();
    }
}
