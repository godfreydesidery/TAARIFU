package com.taarifu.identity.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;

/**
 * Signals that a <b>consumed</b> refresh token was re-presented — the S-3 token-theft signal that fires the
 * family kill-switch ({@code TokenService.rotate(...)}; AUTH-DESIGN §5.1, ADR-0007, ADR-0011 §2).
 *
 * <p>Responsibility: be the <b>one</b> exception {@code rotate(...)} is told NOT to roll back on
 * ({@code @Transactional(noRollbackFor = RefreshTokenReuseException.class)}). It carries
 * {@link ErrorCode#FORBIDDEN}, so the {@code GlobalExceptionHandler}'s {@code ApiException} handler maps it
 * to a 403 exactly like a raw {@code ApiException(FORBIDDEN)} — the caller is still forced to re-login.</p>
 *
 * <p><b>WHY a dedicated type (the rollback-undoes-revoke trap, fixed correctly):</b> on reuse-detection the
 * rotate path must do two things that fight each other under one transaction — <b>commit</b> the family
 * revocation (the kill-switch) yet <b>fail</b> the request with FORBIDDEN. A plain {@code ApiException}
 * throw rolls the transaction back, undoing the revoke (the original S-3 bug: the kill-switch was a no-op).
 * The revoke cannot instead be moved to a separate {@code REQUIRES_NEW} transaction, because
 * {@code rotate(...)} already holds a {@code PESSIMISTIC_WRITE} lock on those family rows
 * ({@code findByTokenHashForUpdate}) — a second, independent transaction updating the same rows
 * <b>self-deadlocks</b> against the still-suspended outer transaction's lock (confirmed empirically: the
 * inner UPDATE blocks forever on the row lock). So the revoke must stay <b>inline</b> in the locked outer
 * transaction (no deadlock — it already owns the locks) and that transaction must <b>commit</b>. Marking
 * <i>only</i> this exception {@code noRollbackFor} lets the FORBIDDEN propagate to the caller while the
 * committed revoke survives. A distinct subclass (not bare {@code ApiException}) keeps the no-rollback
 * carve-out surgical: the other rotate failure paths (unknown/revoked/expired) still roll back normally.</p>
 */
public class RefreshTokenReuseException extends ApiException {

    /** Builds the reuse-detection signal carrying {@link ErrorCode#FORBIDDEN}. */
    public RefreshTokenReuseException() {
        super(ErrorCode.FORBIDDEN);
    }
}
