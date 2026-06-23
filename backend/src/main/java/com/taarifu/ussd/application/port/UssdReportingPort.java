package com.taarifu.ussd.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-owned port describing what the USSD flows need from <b>reporting</b>: the short list of categories
 * to offer, filing a report from the USSD draft, and tracking a report by its ticket code (PRD §14, UC-D02,
 * US-3.9; ADR-0013).
 *
 * <p>Responsibility: capture the reporting contract this module depends on. The USSD file-report flow is the
 * civic-core action — it is gated on <b>tier only</b> (T1 is sufficient for a feature-phone filing — PRD §14,
 * §23 "USSD users get equivalent quotas") and <b>never</b> on a token balance (the civic-integrity fence,
 * D18, §23.5). Nothing on this port takes or returns a token.</p>
 *
 * <p>WHY a consumer-owned interface: reporting publishes read ports ({@code IssueCategoryQueryApi},
 * {@code ReportQueryApi}) and a responder-side lifecycle port, but <b>no citizen-side file/track command
 * port</b> yet (filing is an authenticated controller path). Per the isolation rule this module must not
 * import reporting's {@code application}/{@code domain}, so the seam is defined here, bound to a dev stub
 * now, and delegated to reporting's published file/track ports once they exist ({@code // TODO(wiring)}; see
 * CENTRAL INTEGRATION NEEDS). All ids are public {@code UUID}s; no reporting entity crosses the boundary.</p>
 */
public interface UssdReportingPort {

    /**
     * Lists the issue categories to offer on the USSD menu, kept short (top categories) so the page stays
     * within the GSM-7/182-char USSD limit.
     *
     * @param max the maximum number of categories to return (the menu can only show a few).
     * @return the offered categories (id + short Swahili-first name), in display order.
     */
    List<UssdCategoryOption> topCategories(int max);

    /**
     * Files a report from the assembled USSD draft (US-3.9, UC-D02).
     *
     * <p>The reporter is the MSISDN-linked T1 account; the ward is the registered area or the picked ward;
     * the description is the citizen's free text. No GPS point is collected over USSD (the handset cannot
     * supply one reliably). Gated on tier only — never a token balance (D18).</p>
     *
     * @param reporterPublicId the MSISDN-linked account public id.
     * @param categoryId       the chosen category public id.
     * @param wardId           the resolved ward public id (min pin granularity).
     * @param description       the citizen's short free-text description.
     * @return the filed report's ticket code (TAR-YYYY-NNNNNN), the only handle a feature phone needs.
     */
    String fileReport(UUID reporterPublicId, UUID categoryId, UUID wardId, String description);

    /**
     * Tracks a report by its human ticket code for the USSD track flow.
     *
     * @param ticketCode the ticket code the citizen typed (TAR-YYYY-NNNNNN).
     * @return a short status view, or empty if no such ticket exists.
     */
    Optional<UssdReportStatus> trackByCode(String ticketCode);

    /**
     * A category option offered on the USSD menu — only what the menu needs (id + short name).
     *
     * @param categoryId the category's public id.
     * @param name       the short, GSM-7-safe display name (Swahili-first).
     */
    record UssdCategoryOption(UUID categoryId, String name) {
    }

    /**
     * A minimal status view for the USSD track flow — what fits on one END page.
     *
     * @param ticketCode the ticket code.
     * @param status     the short status label to show the citizen.
     */
    record UssdReportStatus(String ticketCode, String status) {
    }
}
