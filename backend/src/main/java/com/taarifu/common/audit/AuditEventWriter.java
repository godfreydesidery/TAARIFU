package com.taarifu.common.audit;

import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.domain.port.CryptoPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * The default {@link AuditEventService} — persists immutable audit rows append-only (AUTH-DESIGN §11,
 * ADR-0011 §8, L-1).
 *
 * <p>Responsibility: enforces the two non-negotiable audit invariants in one place:</p>
 * <ul>
 *   <li><b>No raw PII</b> (PRD §18, PDPA): the raw client IP is <b>hashed</b> here (via
 *       {@link CryptoPort#blindIndex(String)}) before persistence; callers never store a raw IP, phone,
 *       {@code idNo}, OTP value, or token.</li>
 *   <li><b>Tamper-evidence</b>: each row's {@link AuditEvent#getEntryHash()} chains the previous
 *       event's hash, so a deleted/edited row breaks the chain.</li>
 * </ul>
 *
 * <p>WHY {@link Propagation#REQUIRES_NEW}: an audit write must not be rolled back by — nor roll back —
 * the business transaction it observes. A failed login (which rolls nothing back) and a failed
 * verification decision (which does) must both leave their audit trail intact. The audit row commits in
 * its own transaction. The trade-off (a crash between business-commit and audit-commit could drop one
 * event) is accepted for the auth surface; high-atomicity events may later ride the outbox (ADR-0011
 * revisit b).</p>
 */
@Service
public class AuditEventWriter implements AuditEventService {

    private final AuditEventRepository repository;
    private final CryptoPort crypto;
    private final ClockPort clock;

    /**
     * @param repository append-only audit store.
     * @param crypto     used to hash the client IP (keyed HMAC; same primitive as the blind index).
     * @param clock      time source for the chain (testable).
     */
    public AuditEventWriter(AuditEventRepository repository, CryptoPort crypto, ClockPort clock) {
        this.repository = repository;
        this.crypto = crypto;
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEvent event) {
        persist(event);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEvent.Builder builder, String rawClientIp) {
        if (rawClientIp != null && !rawClientIp.isBlank()) {
            // Hash the IP so the raw value never reaches the table (PDPA, S-5).
            builder.clientIpHash(crypto.blindIndex(rawClientIp));
        }
        persist(builder.build());
    }

    /** Chains and persists one event; shared by both public {@code record} entry points. */
    private void persist(AuditEvent event) {
        String prevHash = currentChainHead();
        event.setChain(prevHash, computeEntryHash(prevHash, event));
        repository.save(event);
    }

    /** @return the most recent entry's chain hash, or {@code null} for the first-ever event. */
    private String currentChainHead() {
        List<AuditEvent> head = repository.findAll(PageRequest.of(0, 1,
                org.springframework.data.domain.Sort.by("id").descending())).getContent();
        return head.isEmpty() ? null : head.get(0).getEntryHash();
    }

    /**
     * Computes {@code entryHash = SHA-256(prevHash ∥ canonical(event))} over the event's non-PII
     * reference fields only — the canonical form deliberately excludes any field that could carry PII.
     */
    private String computeEntryHash(String prevHash, AuditEvent e) {
        String canonical = String.join("|",
                nullSafe(prevHash),
                String.valueOf(clock.now().toEpochMilli()),
                e.getEventType().name(),
                e.getOutcome().name(),
                nullSafe(e.getActorPublicId() == null ? null : e.getActorPublicId().toString()),
                nullSafe(e.getSubjectPublicId() == null ? null : e.getSubjectPublicId().toString()),
                nullSafe(e.getReasonCode()),
                nullSafe(e.getClientIpHash()),
                nullSafe(e.getCorrelationId() == null ? null : e.getCorrelationId().toString()));
        return sha256Hex(canonical);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            // Hashing must not block an audit write; degrade to an empty chain link rather than fail.
            return "";
        }
    }
}
