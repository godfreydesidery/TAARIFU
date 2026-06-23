package com.taarifu.identity.api;

import com.taarifu.identity.api.dto.UserAdminDetail;
import com.taarifu.identity.api.dto.UserAdminFilter;
import com.taarifu.identity.api.dto.UserAdminPage;
import com.taarifu.identity.api.dto.UserAdminSummary;

import java.util.UUID;

/**
 * The identity module's <b>public, in-process query port</b> for the back-office user-management surface
 * (M14, US-14.1, UC-H06; ADR-0013 §1). The {@code admin} module calls this synchronously
 * ({@code admin → identity}) to populate the console's Users area, <b>without</b> importing identity's
 * {@code domain}/{@code repository} (ARCHITECTURE §3.2) — the same shape as the existing
 * {@link com.taarifu.reporting.api.ReportQueryApi} admin-read surface.
 *
 * <p>Responsibility: answer "which accounts match this filter?" and "what is this account's admin view?"
 * Identity owns this because it owns the {@code app_user}/{@code profile}/{@code role_assignment} aggregate;
 * the caller treats every result as opaque truth and never reaches past it. The implementation lives in
 * {@code identity.application.service} as a {@code @Transactional(readOnly = true)} {@code @Service}; the
 * admin caller injects this interface, never the impl (ADR-0013 §1).</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA — data minimisation):</b> every projection this port returns is
 * privacy-minimised — a <b>masked</b> phone only (e.g. {@code +2557****1234}), never the raw E.164 number,
 * <b>never</b> the {@code idNo} (national/voter ID, encrypted at rest), and never any decrypted PII. The
 * admin user surface is for account/role management, not a contact-data export; an operator who needs to
 * reach a citizen uses the proper consent-bound channel, not the dashboard. The immutable {@code publicId}
 * is the only identifier the console acts on (ADR-0006). The admin controller is method-secured to
 * {@code ADMIN}/{@code ROOT}; this port carries no authorization of its own (it trusts the caller's gate).</p>
 */
public interface UserAdminQueryApi {

    /**
     * Lists accounts for the admin user-management table, filtered and paginated.
     *
     * @param filter the optional filter dimensions (name / phone-suffix / tier / role / status); never
     *               {@code null} (pass an all-{@code null} {@link UserAdminFilter} for "no filter"). An
     *               unknown role/status/tier name yields an empty match rather than an error (a stale admin
     *               filter never 500s the table).
     * @param page   zero-based page index (the admin controller caps/normalises before calling).
     * @param size   page size (the admin controller caps before calling).
     * @return a transport-neutral page of privacy-minimised account summaries (never {@code null}; empty
     *         content on an out-of-range page).
     */
    UserAdminPage<UserAdminSummary> listUsers(UserAdminFilter filter, int page, int size);

    /**
     * Loads one account's admin detail view — roles + scopes, tier, status, and the count of pinned
     * locations (no raw PII).
     *
     * @param userPublicId the account's public id.
     * @return the account's admin detail.
     * @throws com.taarifu.common.error.ApiException with {@code NOT_FOUND} if no such account exists.
     */
    UserAdminDetail getUser(UUID userPublicId);
}
