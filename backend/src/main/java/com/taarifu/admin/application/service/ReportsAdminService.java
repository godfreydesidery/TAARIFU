package com.taarifu.admin.application.service;

import com.taarifu.admin.api.dto.AdminStatsDto;
import com.taarifu.admin.api.spi.ModuleStat;
import com.taarifu.admin.api.spi.ModuleStatsProvider;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.reporting.api.ReportQueryApi;
import com.taarifu.reporting.api.dto.AdminReportDetail;
import com.taarifu.reporting.api.dto.AdminReportPage;
import com.taarifu.reporting.api.dto.AdminReportQuery;
import com.taarifu.reporting.api.dto.ReportStatusCount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The admin console's <b>reports &amp; case-management read</b> workflow service (M14, US-14.1, UC-H06).
 *
 * <p>Responsibility: serve the back-office reports queue, staff case detail, and the overview aggregate
 * counts by composing <b>only</b> published cross-module ports — never importing another module's
 * internals (ADR-0013 §1, ARCHITECTURE §3.2). The report queue/detail/counts come from the reporting
 * module's {@link ReportQueryApi} (a sanctioned {@code admin → reporting} api call); the verification-queue
 * depth and flags-pending come from the modules' {@link ModuleStatsProvider} stats by stable key, so the
 * admin module stays decoupled from identity/moderation internals.</p>
 *
 * <p>No {@code @Transactional} here — each port manages its own read transaction over its own tables; this
 * service only orchestrates and shapes the result (counts only ever cross the boundary, never PII —
 * PRD §18). It is the unit-tested seam: the controller is a thin HTTP wrapper.</p>
 */
@Service
public class ReportsAdminService {

    private static final Logger log = LoggerFactory.getLogger(ReportsAdminService.class);

    /**
     * Stable {@link ModuleStat#key()} the <b>identity</b> module is expected to publish for the count of
     * verifications (NIDA/voter-ID) awaiting operator review. WHY a key constant (not an identity import):
     * the depth lives in identity's tables; the admin overview reads it through the published stats SPI so
     * the boundary holds. Until identity publishes this stat the overview shows {@code 0} (degrade
     * gracefully, ARCHITECTURE §1) — recorded as a CENTRAL NEED for the identity owner to publish.
     */
    static final String KEY_VERIFICATION_QUEUE_DEPTH = "identity.verifications.PENDING";

    /**
     * Stable {@link ModuleStat#key()} the <b>moderation</b> module is expected to publish for the count of
     * flags/reports awaiting moderator action. Sourced via the stats SPI for the same boundary reason as
     * {@link #KEY_VERIFICATION_QUEUE_DEPTH}; defaults to {@code 0} until moderation publishes it.
     */
    static final String KEY_FLAGS_PENDING = "moderation.flags.PENDING";

    private final ReportQueryApi reportQueryApi;
    private final List<ModuleStatsProvider> statsProviders;
    private final ClockPort clock;

    /**
     * @param reportQueryApi reporting's published read port (queue/detail/counts) — injected as the
     *                       interface, never the impl (ADR-0013 §1).
     * @param statsProviders every {@link ModuleStatsProvider} Spring found (used to read the
     *                       identity/moderation depth counts by key without importing them); may be empty.
     * @param clock          time source for the {@code generatedAt} stamp (testable).
     */
    public ReportsAdminService(ReportQueryApi reportQueryApi,
                               List<ModuleStatsProvider> statsProviders,
                               ClockPort clock) {
        this.reportQueryApi = reportQueryApi;
        this.statsProviders = statsProviders;
        this.clock = clock;
    }

    /**
     * Returns the owner-grade, PII-minimised reports queue, filtered and paginated.
     *
     * @param filter the (PII-free) filter dimensions (status/category/ward/SLA-breach).
     * @param page   zero-based page index (already capped by the controller).
     * @param size   page size (already capped by the controller).
     * @return the transport-neutral page of queue rows.
     */
    public AdminReportPage listReports(AdminReportQuery filter, int page, int size) {
        return reportQueryApi.adminQuery(filter, page, size);
    }

    /**
     * Returns the staff case detail (including the full internal timeline) for one report.
     *
     * @param reportPublicId the report's public id.
     * @return the staff case detail.
     * @throws com.taarifu.common.error.ApiException {@code NOT_FOUND} if no such report exists.
     */
    public AdminReportDetail caseDetail(UUID reportPublicId) {
        return reportQueryApi.adminDetail(reportPublicId);
    }

    /**
     * Computes the overview aggregate counts the dashboard header needs.
     *
     * <p>Report counts come from reporting's port; the verification-queue depth and flags-pending are read
     * from the stats providers by stable key (0 if no provider publishes them). A provider that throws
     * while we read a key is isolated (logged, treated as 0) so one module can never break the overview.</p>
     *
     * @return the aggregate counts + the generation instant.
     */
    public AdminStatsDto stats() {
        List<ReportStatusCount> byStatus = reportQueryApi.reportCountsByStatus();
        long openCases = reportQueryApi.openCaseCount();
        long slaBreached = reportQueryApi.slaBreachedCount();
        long verificationQueueDepth = statByKey(KEY_VERIFICATION_QUEUE_DEPTH);
        long flagsPending = statByKey(KEY_FLAGS_PENDING);
        return new AdminStatsDto(byStatus, openCases, slaBreached, verificationQueueDepth, flagsPending,
                clock.now());
    }

    /**
     * Reads a single headline count from the published module stats by its stable key, with fault
     * isolation: a missing key (no module publishes it) yields {@code 0}; a throwing provider is logged and
     * skipped (it must not break the overview — ARCHITECTURE §1 degrade gracefully).
     */
    private long statByKey(String key) {
        for (ModuleStatsProvider provider : statsProviders) {
            try {
                List<ModuleStat> stats = provider.stats();
                if (stats == null) {
                    continue;
                }
                for (ModuleStat stat : stats) {
                    if (key.equals(stat.key())) {
                        return stat.value();
                    }
                }
            } catch (RuntimeException ex) {
                // Isolate: a buggy provider must not blank the overview (no PII — counts only).
                log.warn("Stats provider '{}' failed while reading key '{}'; treating as unavailable",
                        safeSection(provider), key, ex);
            }
        }
        return 0L;
    }

    /** Reads a provider's section name defensively for logging (a buggy provider must not crash this). */
    private String safeSection(ModuleStatsProvider provider) {
        try {
            return provider.section();
        } catch (RuntimeException ex) {
            return provider.getClass().getSimpleName();
        }
    }
}
