package com.taarifu.identity.domain.port;

/**
 * Outbound port for verifying a citizen's government identity (NIDA / voter ID) — PRD §21 EI-1/EI-2,
 * ADR-0004, ARCHITECTURE.md §7.
 *
 * <p>Responsibility: abstracts "confirm this person's national/voter ID". Adapters (selected by
 * config) include an <b>operator-assisted</b> path (the MVP default, backed by the
 * {@code VerificationRequest} queue), a future <b>NIDA API</b> adapter, a <b>voter-ID</b> adapter, and a
 * <b>stub</b>. The voter-ID result is authoritative for the electoral location (D13); the NIDA result
 * establishes identity (PRD §9.0).</p>
 *
 * <p>Degradation (EI-1/EI-2): if an automated provider is unavailable, verification falls back to the
 * operator-assisted queue — and a citizen <b>never loses an already-earned tier</b> while a check is
 * pending (PRD §21). This interface intentionally has <b>no implementation in this increment</b>
 * (FOUNDATION-SCOPE.md §5 — identity is data-layer only); the adapters land with the auth increment.
 * The interface is declared now so the model and the port seam are locked.</p>
 */
public interface IdentityVerificationProvider {

    /**
     * Submits an identity for verification.
     *
     * @param idTypeName the ID document type name (e.g. {@code NATIONAL}, {@code VOTER}).
     * @param idNumber   the document number (PII — never logged by adapters; PRD §18).
     * @param fullName   the claimed full name to match.
     * @return the verification outcome (matched/pending/rejected), as defined by the implementing
     *         increment. Method signature is provisional and finalised when the first adapter is built.
     */
    VerificationOutcome verify(String idTypeName, String idNumber, String fullName);

    /**
     * Coarse result of a verification attempt. Refined (e.g. with a reason and a confidence) when the
     * first real adapter is implemented in the auth increment.
     */
    enum VerificationOutcome {

        /** The identity matched and is confirmed. */
        MATCHED,

        /** No automated decision; routed to the operator-assisted queue (degradation — EI-1/EI-2). */
        PENDING_REVIEW,

        /** The identity did not match / was rejected. */
        REJECTED
    }
}
