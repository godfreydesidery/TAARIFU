package com.taarifu.common.audit;

import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.domain.port.CryptoPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditEventWriter} — L-1 / S-4 / S-5 (AUTH-DESIGN §11).
 *
 * <p>Responsibility: proves the two audit invariants without a DB: the raw client IP is <b>hashed</b>
 * before persistence (never stored raw — PDPA/S-5), and the writer chains + persists the event
 * (append-only). The raw IP must never appear on the saved row.</p>
 */
class AuditEventWriterTest {

    @Test
    void clientIp_isHashed_neverStoredRaw() {
        AuditEventRepository repo = mock(AuditEventRepository.class);
        CryptoPort crypto = mock(CryptoPort.class);
        ClockPort clock = Instant::now;
        when(crypto.blindIndex("203.0.113.7")).thenReturn("HASHED_IP");
        Page<AuditEvent> emptyHead = new PageImpl<>(List.of());
        when(repo.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(emptyHead);

        AuditEventWriter writer = new AuditEventWriter(repo, crypto, clock);

        writer.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_LOGIN_FAILED, AuditOutcome.FAILURE)
                .actor(UUID.randomUUID())
                .reason("INVALID_CREDENTIALS"), "203.0.113.7");

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(saved.capture());
        AuditEvent event = saved.getValue();

        // The stored ip-hash is the hashed value; the raw IP never appears.
        assertThat(event.getClientIpHash()).isEqualTo("HASHED_IP");
        assertThat(event.getClientIpHash()).doesNotContain("203.0.113.7");
        assertThat(event.getEntryHash()).isNotBlank(); // tamper-evidence chain set
        verify(crypto).blindIndex("203.0.113.7");
    }

    @Test
    void event_isPersistedWithChainHash() {
        AuditEventRepository repo = mock(AuditEventRepository.class);
        CryptoPort crypto = mock(CryptoPort.class);
        when(repo.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        AuditEventWriter writer = new AuditEventWriter(repo, crypto, Instant::now);
        writer.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_SIGNUP_COMPLETED, AuditOutcome.SUCCESS)
                .actor(UUID.randomUUID()).build());

        verify(repo).save(any(AuditEvent.class));
    }
}
