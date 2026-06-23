package com.taarifu.identity.infrastructure.adapter;

import com.taarifu.identity.domain.port.IdentityVerificationProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The MVP / default {@link IdentityVerificationProvider} — routes every submission to the
 * <b>operator-assisted</b> Moderator queue (D-Q2, VERIFICATION-DESIGN §2.1).
 *
 * <p>Responsibility: this adapter <b>never auto-decides</b>. It always returns
 * {@link VerificationOutcome#PENDING_REVIEW}, so {@code VerificationService} creates a
 * {@code VerificationRequest(PENDING)} that a {@code MODERATOR} approves/rejects. There is no external
 * call here, so there is nothing to fail — the operator path <i>is</i> the degradation mode for the
 * future NIDA/voter adapters (DI2): if a real registry is later unavailable, it falls back to exactly
 * this queue and a citizen never loses an already-earned tier while pending (PRD §21, EI-1/EI-2).</p>
 *
 * <p>Selected by config {@code taarifu.identity.verification.provider=operator-assisted} (the default
 * when the key is absent, via {@code matchIfMissing=true}). The auto/NIDA stub
 * ({@link StubAutoVerificationAdapter}) is selected by {@code =auto-stub}.</p>
 *
 * <p>PDPA (§18): the {@code idNumber} is PII; this adapter <b>never</b> logs it (it does not log at
 * all). Only the coarse {@link VerificationOutcome} crosses the seam — no ward/constituency semantics
 * and no vendor type leak into the domain.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.identity.verification.provider",
        havingValue = "operator-assisted", matchIfMissing = true)
public class OperatorAssistedVerificationAdapter implements IdentityVerificationProvider {

    /**
     * {@inheritDoc}
     *
     * <p>Always {@link VerificationOutcome#PENDING_REVIEW}: the decision belongs to a human Moderator in
     * this MVP path (D-Q2). The parameters are accepted to honour the port contract but are neither
     * stored nor logged here — the encrypted {@code idNo} and the {@code VerificationRequest} carry the
     * durable state.</p>
     */
    @Override
    public VerificationOutcome verify(String idTypeName, String idNumber, String fullName) {
        return VerificationOutcome.PENDING_REVIEW;
    }
}
