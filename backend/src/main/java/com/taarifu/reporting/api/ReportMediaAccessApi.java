package com.taarifu.reporting.api;

import java.util.UUID;

/**
 * The reporting module's <b>public, in-process visibility port</b> answering "may this caller view the
 * media/evidence attached to this report?" for the media serve path (ADR-0013 §1, §4a; MF-2, OWASP A01).
 *
 * <p><b>WHY this exists (MF-2 — Broken Object-Level Authorization / IDOR).</b> A media object is bound to a
 * host report by id only; the media module must not import reporting's internals (ARCHITECTURE.md §3.2),
 * so it cannot itself decide whether a caller may see a private/anonymous-sensitive report's evidence.
 * Without this delegation the only gate on issuing a pre-signed download was scan-state = CLEAN, which lets
 * <i>any</i> authenticated citizen who knows a media {@code publicId} pull evidence attached to a
 * <b>PRIVATE</b> or <b>anonymous sensitive</b> (corruption/GBV) report — exactly the citizen-safety/PDPA
 * failure the launch gate exists to prevent (PRD §25.3, §18). The media module calls this port on the
 * download path and fails closed.</p>
 *
 * <h3>The visibility model (the load-bearing rule)</h3>
 * <ul>
 *   <li><b>PUBLIC report</b> — its evidence is viewable by any authenticated caller (the report itself is
 *       publicly discoverable; the media is not more sensitive than the report).</li>
 *   <li><b>PRIVATE report</b> (this includes every sensitive category <b>forced</b> PRIVATE — GBV,
 *       corruption, Appendix D.4/D-Q1) — its evidence is viewable ONLY by (a) the <b>reporter</b> (the
 *       caller's account public id equals the report's reporter), or (b) an <b>authorized in-scope staff
 *       principal</b> (a moderator/admin, or a responder/representative whose live area+category scope
 *       covers the report — R-1). An <b>anonymous</b> sensitive filing has no reporter linkage, so branch
 *       (a) can never match for any citizen — only in-scope staff can ever view it.</li>
 * </ul>
 *
 * <h3>Privacy (PRD §18 / PDPA) & boundary</h3>
 * <p>The port takes and returns bare ids/booleans only — never the reporter's profile/phone/{@code idNo},
 * the report body, or the geo-point (data minimisation). It is fail-closed: a {@code null}/unknown report,
 * a {@code null} caller, or any branch that does not affirmatively grant returns {@code false}. The staff
 * scope decision reads the caller's <b>live</b> {@code RoleAssignment} scope from the database (never the
 * token), consistent with the responders' R-1 horizontal-authorization gate.</p>
 */
public interface ReportMediaAccessApi {

    /**
     * Decides whether {@code callerAccountId} may view the media/evidence bound to {@code reportPublicId}.
     *
     * <p>Fail-closed: returns {@code false} for a {@code null}/unknown report id, a {@code null} caller, or
     * any case not affirmatively granted by the visibility model above. The caller (the media serve path)
     * has already authenticated the principal and gated on scan-state; this answers the host-visibility
     * question only.</p>
     *
     * @param reportPublicId  the host report's public id (the media's bound {@code ownerId}).
     * @param callerAccountId the authenticated caller's account public id (the JWT subject); never trusted
     *                        for roles — staff scope is re-resolved live server-side.
     * @return {@code true} only if the caller is the reporter of, or an authorized in-scope staff viewer
     *         for, this report; {@code false} otherwise (deny-by-default).
     */
    boolean canViewReportMedia(UUID reportPublicId, UUID callerAccountId);
}
