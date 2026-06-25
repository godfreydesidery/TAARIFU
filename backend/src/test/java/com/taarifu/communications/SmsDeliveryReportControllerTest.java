package com.taarifu.communications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.communications.api.controller.SmsDeliveryReportController;
import com.taarifu.communications.application.service.SmsDeliveryReportService;
import com.taarifu.communications.infrastructure.config.SmsDlrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link SmsDeliveryReportController} — proves the DLR webhook is shared-secret authenticated
 * and fail-closed, parses the configured fields, and delegates to the service, with no Spring/network
 * (Mockito + a real {@link ObjectMapper}; CLAUDE.md §10, PRD §18).
 *
 * <p>Responsibility: pins the load-bearing webhook auth + parsing contract — a valid secret + delivered
 * status delegates {@code apply(reference, true, …)}; a non-delivered status delegates
 * {@code apply(reference, false, reason)}; a <b>wrong/missing secret</b> and a context with <b>no secret
 * configured</b> both reject with <b>no service call</b> (fail-closed, no oracle); an unparseable body is a
 * benign ignore. The endpoint always returns the benign envelope so the aggregator never retry-storms.</p>
 */
class SmsDeliveryReportControllerTest {

    private static final String SECRET = "super-secret-dlr-key";

    private SmsDeliveryReportService dlrService;
    private ResponseFactory responses;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dlrService = mock(SmsDeliveryReportService.class);
        // The controller's return value is not asserted (it always returns the benign envelope); an
        // unstubbed ResponseFactory.ok(...) returns null, which is a valid ApiResponse<Void> here. The
        // tests assert the service interactions (auth gate + parsed delegation), not the envelope body.
        responses = mock(ResponseFactory.class);
    }

    private SmsDeliveryReportController controllerWithSecret(String secret) {
        SmsDlrProperties props = new SmsDlrProperties(secret, null, null, null, null);
        return new SmsDeliveryReportController(dlrService, props, objectMapper, responses);
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void validSecret_deliveredStatus_delegatesAsDelivered() {
        SmsDeliveryReportController controller = controllerWithSecret(SECRET);

        controller.handle(SECRET, body("{\"reference\":\"abc-123\",\"status\":\"DELIVERED\"}"));

        verify(dlrService).apply(eq("abc-123"), eq(true), any());
    }

    @Test
    void validSecret_failedStatus_delegatesAsFailure_withReason() {
        SmsDeliveryReportController controller = controllerWithSecret(SECRET);

        controller.handle(SECRET, body("{\"reference\":\"abc-123\",\"status\":\"EXPIRED\"}"));

        verify(dlrService).apply("abc-123", false, "EXPIRED");
    }

    @Test
    void wrongSecret_rejects_withNoServiceCall() {
        SmsDeliveryReportController controller = controllerWithSecret(SECRET);

        controller.handle("not-the-secret", body("{\"reference\":\"abc-123\",\"status\":\"DELIVERED\"}"));

        verify(dlrService, never()).apply(any(), any(Boolean.class), any());
    }

    @Test
    void missingSecretHeader_rejects_withNoServiceCall() {
        SmsDeliveryReportController controller = controllerWithSecret(SECRET);

        controller.handle(null, body("{\"reference\":\"abc-123\",\"status\":\"DELIVERED\"}"));

        verify(dlrService, never()).apply(any(), any(Boolean.class), any());
    }

    @Test
    void noSecretConfigured_failsClosed_evenWithAPresentedHeader() {
        // No secret configured → the webhook authenticates nothing (a misconfiguration never opens it).
        SmsDeliveryReportController controller = controllerWithSecret(null);

        controller.handle("anything", body("{\"reference\":\"abc-123\",\"status\":\"DELIVERED\"}"));

        verify(dlrService, never()).apply(any(), any(Boolean.class), any());
    }

    @Test
    void unparseableBody_isBenignlyIgnored() {
        SmsDeliveryReportController controller = controllerWithSecret(SECRET);

        controller.handle(SECRET, body("not json"));

        verify(dlrService, never()).apply(any(), any(Boolean.class), any());
    }

    @Test
    void nullBody_isBenignlyIgnored() {
        SmsDeliveryReportController controller = controllerWithSecret(SECRET);

        controller.handle(SECRET, null);

        verify(dlrService, never()).apply(any(), any(Boolean.class), any());
    }
}
