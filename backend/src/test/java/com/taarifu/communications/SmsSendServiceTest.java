package com.taarifu.communications;

import com.taarifu.communications.api.SmsSendApi;
import com.taarifu.communications.application.service.SmsSendService;
import com.taarifu.communications.domain.port.SmsGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SmsSendService} — the published {@code SmsSendApi} impl that bridges to the internal
 * {@code SmsGateway} (A3, ADR-0019).
 *
 * <p>Asserts the façade maps the public command to the internal message faithfully (recipient/body/purpose/
 * idempotency key) and maps both the accepted and failed internal results back to the public result — so the
 * USSD adapter sees an honest outcome and the aggregator's masking discipline stays owned inside the gateway.</p>
 */
class SmsSendServiceTest {

    private final SmsGateway gateway = mock(SmsGateway.class);
    private final SmsSendService service = new SmsSendService(gateway);

    /** The command is mapped 1:1 onto the internal gateway message; an accepted send returns accepted. */
    @Test
    void send_mapsCommandToGatewayMessage_andReturnsAccepted() {
        when(gateway.send(org.mockito.ArgumentMatchers.any()))
                .thenReturn(SmsGateway.SmsSendResult.accepted("prov-1"));

        SmsSendApi.SmsSendResult result = service.send(new SmsSendApi.SmsSendCommand(
                "+255712345678", "Tikiti: TAR-2026-000007", "USSD_TICKET", "sess-1:FILE"));

        ArgumentCaptor<SmsGateway.SmsMessage> captor = ArgumentCaptor.forClass(SmsGateway.SmsMessage.class);
        verify(gateway).send(captor.capture());
        SmsGateway.SmsMessage sent = captor.getValue();
        assertThat(sent.recipientE164()).isEqualTo("+255712345678");
        assertThat(sent.body()).isEqualTo("Tikiti: TAR-2026-000007");
        assertThat(sent.purpose()).isEqualTo("USSD_TICKET");
        assertThat(sent.idempotencyKey()).isEqualTo("sess-1:FILE");

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("prov-1");
    }

    /** A failed gateway result maps to a failed public result with the non-PII reason preserved. */
    @Test
    void send_mapsGatewayFailureToFailedResult() {
        when(gateway.send(org.mockito.ArgumentMatchers.any()))
                .thenReturn(SmsGateway.SmsSendResult.failed("AGGREGATOR_DOWN"));

        SmsSendApi.SmsSendResult result = service.send(new SmsSendApi.SmsSendCommand(
                "+255712345678", "x", "USSD_TICKET", "k"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).isEqualTo("AGGREGATOR_DOWN");
    }
}
