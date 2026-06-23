package com.taarifu.identity.infrastructure.adapter;

import com.taarifu.identity.domain.port.IdentityVerificationProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The auto / NIDA <b>stub</b> {@link IdentityVerificationProvider} — a deterministic, no-external-call
 * decision for dev / E2E (D-Q2, VERIFICATION-DESIGN §2.1). It is the seam where the future real NIDA /
 * voter-ID registry adapter slots in without touching the domain (EI-1/EI-2).
 *
 * <p>Responsibility: returns {@link VerificationOutcome#REJECTED} for an ID number containing the
 * marker {@code "REJECT"} (so negative paths are testable) and {@link VerificationOutcome#MATCHED}
 * otherwise. The real adapter will instead call the registry, returning {@code MATCHED} on a hit and —
 * critically — {@link VerificationOutcome#PENDING_REVIEW} on outage so it falls back to the operator
 * queue and the citizen never loses an earned tier (degradation, DI2/PRD §21).</p>
 *
 * <p>Selected by config {@code taarifu.identity.verification.provider=auto-stub}; otherwise the
 * operator-assisted adapter is active. PDPA (§18): the {@code idNumber} is PII and is <b>never</b>
 * logged here.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.identity.verification.provider", havingValue = "auto-stub")
public class StubAutoVerificationAdapter implements IdentityVerificationProvider {

    /** Marker substring that forces a deterministic rejection (for negative-path tests). */
    private static final String REJECT_MARKER = "REJECT";

    /**
     * {@inheritDoc}
     *
     * <p>Deterministic and side-effect-free: no external call, no logging of {@code idNumber}. An
     * {@code idNumber} carrying {@link #REJECT_MARKER} rejects; everything else matches.</p>
     */
    @Override
    public VerificationOutcome verify(String idTypeName, String idNumber, String fullName) {
        if (idNumber != null && idNumber.toUpperCase().contains(REJECT_MARKER)) {
            return VerificationOutcome.REJECTED;
        }
        return VerificationOutcome.MATCHED;
    }
}
