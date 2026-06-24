package com.taarifu.reporting.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The reporting module's <b>public, in-process command port</b> for the feature-phone (USSD) citizen
 * file/track flow (ADR-0013 §1, §4d; PRD §14, UC-D02, US-3.9). The {@code ussd} module calls this
 * synchronously ({@code ussd → reporting}) to offer the category menu, file a report from the assembled USSD
 * draft, and track a report by its ticket code — <b>without</b> importing reporting's {@code domain}/
 * {@code application} (ARCHITECTURE §3.2). Reporting stays the single owner of the {@code Report} aggregate
 * and its §12.1 state machine; the caller sees only public {@code UUID}s/codes and the small DTOs below.
 *
 * <p>WHY a dedicated citizen file/track command port (separate from the responder-side
 * {@link ReportLifecycleApi} and the read-side {@link ReportQueryApi}): the existing citizen file path is an
 * authenticated <i>controller</i> method ({@code ReportController.file} → {@code ReportService.fileReport}),
 * not a published port — a USSD dialogue has no HTTP request/JWT to ride. This port exposes the same
 * service-level filing as the channel-agnostic contract the feature phone needs, at the same tier floor
 * relaxed for the USSD reality (T1 sufficient — PRD §14, §23 "USSD users get equivalent quotas").</p>
 *
 * <p><b>🔒 Civic-integrity fence (D18, §23.5):</b> filing is a civic-core action gated on the reporter's T1
 * account (tier) only — there is deliberately <b>no</b> token/balance input or output anywhere on this port,
 * and nothing here reads a wallet. A feature-phone citizen can never be priced or gated out of being heard.</p>
 */
public interface UssdReportApi {

    /**
     * Lists the issue categories to offer on the USSD menu, kept short (top active categories) so the page
     * stays within the GSM-7/182-char USSD limit.
     *
     * @param max the maximum number of categories to return (the menu can only show a few; {@code <= 0}
     *            yields an empty list).
     * @return the offered categories (public id + Swahili-first display name), in display order (never
     *         {@code null}).
     */
    List<UssdCategoryOption> topCategories(int max);

    /**
     * Files a report from the assembled USSD draft and returns its ticket code (US-3.9, UC-D02).
     *
     * <p>The reporter is the MSISDN-linked T1 account; the category and ward are the picked public ids; the
     * text is the citizen's short free text. No GPS point, attachments, anonymity, or custom visibility are
     * collected over USSD (the handset cannot supply them reliably) — the report files non-anonymously under
     * the category's default visibility, and the standard SLA/routing/outbox path applies exactly as a
     * web/app filing (the routing event is emitted by reporting in the same transaction). Gated on tier only,
     * never a token balance (D18).</p>
     *
     * @param reporterAccountId the MSISDN-linked account public id (the reporter handle; never {@code null}).
     * @param categoryPublicId  the chosen issue category's public id (as offered by {@link #topCategories}).
     * @param wardPublicId      the resolved ward public id (minimum pin granularity, PRD §9.0).
     * @param text              the citizen's short free-text description.
     * @return the filed report's ticket code ({@code TAR-YYYY-NNNNNN}) — the only handle a feature phone needs.
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND} if the category or ward is unknown,
     *         {@code BAD_REQUEST} if the category is inactive or the text is blank.
     */
    String fileFromUssd(UUID reporterAccountId, UUID categoryPublicId, UUID wardPublicId, String text);

    /**
     * Tracks a report by its human ticket code for the USSD track flow (US-3.2).
     *
     * <p>Returns only the code + a short status label — the minimal, PII-free slice that fits one USSD END
     * page (data minimisation, PRD §18); no reporter identity, description, or geo-point crosses the boundary.
     * A non-existent code yields empty (the caller shows a "not found" END), never revealing whether a code
     * exists for someone else.</p>
     *
     * @param ticketCode the ticket code the citizen typed ({@code TAR-YYYY-NNNNNN}); case-insensitive.
     * @return a short status view, or empty if no such ticket exists.
     */
    Optional<UssdReportStatus> trackByTicket(String ticketCode);

    /**
     * A category option offered on the USSD menu — only what the menu needs (id + short name).
     *
     * @param categoryId the category's public id.
     * @param name       the short, Swahili-first display name.
     */
    record UssdCategoryOption(UUID categoryId, String name) {
    }

    /**
     * A minimal, PII-free status view for the USSD track flow — what fits on one END page.
     *
     * @param ticketCode the ticket code.
     * @param status     the short lifecycle status label to show the citizen.
     */
    record UssdReportStatus(String ticketCode, String status) {
    }
}
