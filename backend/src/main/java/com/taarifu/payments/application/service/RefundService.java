package com.taarifu.payments.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.TopUpStatus;
import com.taarifu.payments.domain.port.WalletReversalPort;
import com.taarifu.payments.domain.repository.TopUpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Application service for the <b>refund / void</b> half of the top-up lifecycle (ADR-0015 addendum;
 * PRD §23.5, §18, D18).
 *
 * <p>Responsibility: an administrator reverses a top-up that should not stand —
 * <ul>
 *   <li><b>refund</b> a <i>settled</i> top-up (SUCCEEDED → REFUNDED): reverse the convenience-token credit on
 *       the wallet, idempotently, and audit it; or</li>
 *   <li><b>void</b> an <i>un-settled</i> attempt (INITIATED/PENDING → VOIDED): cancel the collection with no
 *       wallet effect (nothing was ever credited).</li>
 * </ul>
 * Both transitions are recorded under a row write-lock and carry a redacted machine reason (never PII).</p>
 *
 * <p><b>Idempotency (PRD §23.5, in reverse):</b> the refund reversal is keyed on a stable per-top-up
 * {@code reversal_event_id} (derived deterministically from the top-up public id) so a retried refund
 * reverses the wallet exactly once. A refund of an already-REFUNDED row is a no-op (returns the row); a void
 * of an already-terminal row is rejected with a typed {@code CONFLICT}.</p>
 *
 * <p><b>🔒 Civic-integrity fence (binding — D18, PRD §23.5):</b> a refund/void touches <b>only</b> the
 * convenience wallet via {@link WalletReversalPort}. It never grants or revokes a role, a vote, a signature, a
 * rating, a poll outcome, routing/SLA/priority, or a verification status, and it never reads a balance for any
 * authorization. Just as a purchase never buys democratic weight, a refund never touches it. This service has
 * <b>no</b> dependency on any binding-action module — the fence is preserved by construction.</p>
 *
 * <p><b>Atomicity (ADR-0014):</b> the REFUNDED write and the wallet reversal happen in <b>one transaction</b>
 * — a crash can never leave the row REFUNDED without its reversal, nor reverse without recording it.</p>
 */
@Service
@Transactional
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final TopUpRepository topUps;
    private final WalletReversalPort walletReversal;

    /**
     * @param topUps         top-up persistence (locked load by public id).
     * @param walletReversal the fence-safe reversal port (tokens-api adapter or logging stub).
     */
    public RefundService(TopUpRepository topUps, WalletReversalPort walletReversal) {
        this.topUps = topUps;
        this.walletReversal = walletReversal;
    }

    /**
     * Refunds a settled top-up: reverses the wallet credit and moves SUCCEEDED → REFUNDED, idempotently.
     *
     * @param publicId the top-up public id.
     * @param reason   a redacted machine reason (e.g. {@code DUPLICATE_CHARGE}); never PII. Required.
     * @return the up-to-date {@link TopUp} (REFUNDED).
     * @throws ResourceNotFoundException if no such top-up exists.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if {@code reason} is blank;
     *                      {@link ErrorCode#CONFLICT} if the top-up is not in a refundable (SUCCEEDED) state
     *                      and is not already REFUNDED.
     */
    public TopUp refund(UUID publicId, String reason) {
        requireReason(reason);
        TopUp topUp = topUps.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("payments.topUp.notFound", publicId));

        // Idempotent: a redelivered/retried refund on an already-reversed row reverses nothing again.
        if (topUp.getStatus() == TopUpStatus.REFUNDED) {
            return topUp;
        }
        if (!topUp.isRefundable()) {
            // Only a SUCCEEDED top-up can be reversed; an un-settled attempt is VOIDed, not refunded.
            throw new ApiException(ErrorCode.CONFLICT);
        }

        // Stable, per-top-up reversal idempotency key → the ledger reverses exactly once even on retry.
        UUID reversalKey = UUID.nameUUIDFromBytes(("topup-reversal:" + topUp.getPublicId()).getBytes());
        // The (non-PII) machine reason is threaded to the tokens ledger so the REFUND entry is self-explaining.
        walletReversal.reverseTopUp(topUp.getWalletOwnerType(), topUp.getBuyerId(),
                topUp.getTokenAmount(), reversalKey.toString(), reason);

        topUp.markRefunded(reversalKey, reason);
        TopUp saved = topUps.save(topUp);
        log.info("Top-up refunded and wallet reversed: provider={}, tokenAmount={}",
                saved.getProvider(), saved.getTokenAmount());
        return saved;
    }

    /**
     * Voids an un-settled top-up attempt (INITIATED/PENDING → VOIDED) — an admin cancellation with no wallet
     * effect (nothing was credited).
     *
     * @param publicId the top-up public id.
     * @param reason   a redacted machine reason (e.g. {@code ADMIN_CANCELLED}); never PII. Required.
     * @return the up-to-date {@link TopUp} (VOIDED).
     * @throws ResourceNotFoundException if no such top-up exists.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if {@code reason} is blank;
     *                      {@link ErrorCode#CONFLICT} if the top-up is already settlement-terminal (SUCCEEDED
     *                      — must be refunded, not voided — FAILED, VOIDED, or REFUNDED).
     */
    public TopUp voidTopUp(UUID publicId, String reason) {
        requireReason(reason);
        TopUp topUp = topUps.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("payments.topUp.notFound", publicId));
        if (topUp.isTerminal()) {
            // A settled or already-terminal row cannot be voided (a SUCCEEDED row must be refunded instead).
            throw new ApiException(ErrorCode.CONFLICT);
        }
        topUp.markVoided(reason);
        TopUp saved = topUps.save(topUp);
        log.info("Top-up voided (un-settled cancel): provider={}", saved.getProvider());
        return saved;
    }

    /**
     * Guards that a void/refund carries a non-blank reason (auditability — a reversal must explain itself).
     *
     * @param reason the supplied reason.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if blank.
     */
    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
    }
}
