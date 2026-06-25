package com.taarifu.ussd.infrastructure.adapter;

import com.taarifu.reporting.api.UssdReportApi;
import com.taarifu.ussd.application.port.UssdReportingPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Production {@link UssdReportingPort} adapter — delegates to reporting's published {@link UssdReportApi}
 * (the sanctioned synchronous {@code ussd → reporting} contract, ADR-0013 §4d).
 *
 * <p>Responsibility: bind the USSD module's consumer-owned reporting seam to reporting's real citizen
 * file/track command port, so the feature-phone menu offers the real active categories, files a real
 * {@code Report} (same lifecycle/SLA/ticket-code/routing path as a web filing), and tracks a real ticket
 * code — no in-process stub any more. This replaces the prior fixed-list/mint stub and is fully wired to the
 * live reporting port.</p>
 *
 * <p>This adapter holds <b>no logic</b> — it maps between the two modules' equivalent small records and
 * delegates by {@code UUID}/code only (never a reporting entity), the ADR-0013 pattern. No token is read on
 * the file path (the civic-integrity fence, D18, §23.5) — neither {@link UssdReportApi} nor this adapter has
 * any token collaborator.</p>
 */
@Component
public class UssdReportingAdapter implements UssdReportingPort {

    private final UssdReportApi reportApi;

    /**
     * @param reportApi reporting's published USSD file/track command port.
     */
    public UssdReportingAdapter(UssdReportApi reportApi) {
        this.reportApi = reportApi;
    }

    /** {@inheritDoc} */
    @Override
    public List<UssdCategoryOption> topCategories(int max) {
        // Map reporting's category options into this module's equivalent record (no entity crosses).
        return reportApi.topCategories(max).stream()
                .map(c -> new UssdCategoryOption(c.categoryId(), c.name()))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public String fileReport(UUID reporterPublicId, UUID categoryId, UUID wardId, String description) {
        return reportApi.fileFromUssd(reporterPublicId, categoryId, wardId, description);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UssdReportStatus> trackByCode(String ticketCode) {
        return reportApi.trackByTicket(ticketCode)
                .map(s -> new UssdReportStatus(s.ticketCode(), s.status()));
    }
}
