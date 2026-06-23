package com.taarifu.ussd.infrastructure.adapter;

import com.taarifu.ussd.application.port.UssdReportingPort;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link UssdReportingPort} adapter — offers a small fixed category list, mints a plausible ticket
 * code on file, and tracks codes filed in-process, so the USSD flows run end-to-end with <b>zero external
 * calls</b> (ARCHITECTURE §7 stub principle).
 *
 * <p>Responsibility: stand in for reporting's (not-yet-published) citizen file/track command port. The
 * categories are GSM-7-safe Swahili-first labels with <b>stable deterministic ids</b> (so a category chosen
 * in one step is valid at confirm time). Filing mints a {@code TAR-YYYY-NNNNNN}-shaped code and records it
 * so the track flow can echo a status. No token is read on file — the civic-integrity fence holds (D18).</p>
 *
 * <p>This is a scaffold: the production adapter delegates to reporting's published file/track ports and the
 * real {@code IssueCategoryQueryApi} ({@code // TODO(wiring)}; see CENTRAL INTEGRATION NEEDS).</p>
 */
@Component
public class StubUssdReportingAdapter implements UssdReportingPort {

    /** Fixed, GSM-7-safe top categories with stable ids (pilot set: Water/Roads/Health/Sanitation, PRD). */
    private static final List<UssdCategoryOption> CATEGORIES = List.of(
            new UssdCategoryOption(stableId("MAJI"), "Maji"),
            new UssdCategoryOption(stableId("BARABARA"), "Barabara"),
            new UssdCategoryOption(stableId("AFYA"), "Afya"),
            new UssdCategoryOption(stableId("USAFI"), "Usafi"));

    /** Per-run ticket sequence (stub only; real codes come from reporting's DB sequence). */
    private final AtomicInteger seq = new AtomicInteger(1);

    /** Filed tickets this run, so the track flow can echo a status. */
    private final Map<String, String> statusByCode = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public List<UssdCategoryOption> topCategories(int max) {
        // TODO(wiring): source from reporting.api.IssueCategoryQueryApi (active categories, localised).
        return CATEGORIES.stream().limit(Math.max(0, max)).toList();
    }

    /** {@inheritDoc} */
    @Override
    public String fileReport(UUID reporterPublicId, UUID categoryId, UUID wardId, String description) {
        // TODO(wiring): delegate to reporting's published citizen file-report command port (tier-gated, never
        // token-gated — D18) once it exists in com.taarifu.reporting.api (ADR-0013 §1).
        String code = "TAR-" + Year.now() + "-" + String.format("%06d", seq.getAndIncrement());
        statusByCode.put(code, "NEW");
        return code;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UssdReportStatus> trackByCode(String ticketCode) {
        // TODO(wiring): delegate to reporting.api.ReportQueryApi/track-by-code once published.
        String status = statusByCode.get(ticketCode == null ? null : ticketCode.trim().toUpperCase(java.util.Locale.ROOT));
        return Optional.ofNullable(status).map(s -> new UssdReportStatus(ticketCode, s));
    }

    /** Deterministic stable id for a fixed category label (stub only). */
    private static UUID stableId(String label) {
        return UUID.nameUUIDFromBytes(("ussd-cat|" + label).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
