package com.taarifu.common.audit;

import com.taarifu.common.audit.domain.model.AuditEvent;

/**
 * The append-only audit writer port (AUTH-DESIGN §11, ADR-0011 §8, L-1).
 *
 * <p>Responsibility: the single entry point every module uses to record a security-relevant decision.
 * Lives in the shared kernel ({@code common.audit}) because every module audits through it. The
 * implementation guarantees the privacy invariant — <b>references/hashes only, never raw PII</b>
 * (PRD §18, PDPA): callers pass {@code publicId}s and a raw client IP (which the writer <b>hashes</b>),
 * never a phone, {@code idNo}, OTP value, or raw token.</p>
 *
 * <p>WHY a port (not a concrete bean injected directly): it keeps {@code common} free of a persistence
 * dependency in its contract and lets a future increment swap the in-DB writer for an outbox-backed
 * sink (ADR-0011 revisit trigger b) with no change to call sites.</p>
 */
public interface AuditEventService {

    /**
     * Records an assembled audit event. The implementation hashes any client IP, attaches the
     * tamper-evidence chain, and persists append-only. Callers must never place raw PII on the event.
     *
     * @param event the event to record (built via {@link AuditEvent.Builder}); never holds raw PII.
     */
    void record(AuditEvent event);

    /**
     * Convenience: records an event whose builder carries a <b>raw</b> client IP that this method
     * hashes before persistence (so call sites never hash by hand and never store a raw IP).
     *
     * @param builder       the event builder (without the ip-hash set).
     * @param rawClientIp   the raw client IP to hash, or {@code null} if unavailable.
     */
    void record(AuditEvent.Builder builder, String rawClientIp);
}
