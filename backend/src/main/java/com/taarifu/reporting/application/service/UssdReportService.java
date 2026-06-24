package com.taarifu.reporting.application.service;

import com.taarifu.reporting.api.UssdReportApi;
import com.taarifu.reporting.api.dto.FileReportDto;
import com.taarifu.reporting.api.dto.ReportDto;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.repository.IssueCategoryRepository;
import com.taarifu.reporting.domain.repository.ReportRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Reporting's implementation of the published {@link UssdReportApi} — the synchronous {@code ussd → reporting}
 * citizen file/track seam for the feature-phone channel (ADR-0013 §1, §4d; PRD §14, UC-D02, US-3.9).
 *
 * <p>Responsibility: adapt the lean USSD draft (reporter account id + category id + ward id + free text) into
 * the existing {@link ReportService#fileReport} call — so a USSD filing follows the <b>exact same</b>
 * lifecycle, SLA, ticket-code, and routing-outbox path as a web/app filing (DRY; reporting stays the single
 * writer of the {@code Report} aggregate) — plus the short category menu and the ticket-code lookup. Returns
 * only codes/labels (no entity, no PII) across the boundary.</p>
 *
 * <p><b>Reporter handle:</b> the USSD reporter id is the MSISDN-linked <b>account</b> public id (from
 * {@code AccountProvisioningApi.ensureAccountByMsisdn}); it is passed straight through to
 * {@code fileReport(...)} as the reporter, matching the web {@code ReportController}, which likewise passes
 * the account public id ({@code CurrentUser.requirePublicId()}) as the reporter — the two channels store the
 * same reporter grain so a citizen's USSD and app reports are the same person's.</p>
 *
 * <p><b>🔒 Civic-integrity fence (D18, §23.5):</b> no token/wallet collaborator is injected or referenced —
 * filing over USSD is gated on the reporter's tier only, never a balance. The standard category-driven rules
 * (sensitive/anonymous, force-private) still apply inside {@code fileReport}; USSD does not request anonymity
 * or a custom visibility, so the category default holds.</p>
 */
@Service
@Transactional(readOnly = true)
public class UssdReportService implements UssdReportApi {

    /** Max length of the synthesised title taken from the citizen's free text (the title column is ≤ 200). */
    private static final int TITLE_MAX = 80;

    private final ReportService reportService;
    private final IssueCategoryRepository categoryRepository;
    private final ReportRepository reportRepository;

    /**
     * @param reportService      the core case-management service (single source of the file path).
     * @param categoryRepository active-category lookup for the USSD menu.
     * @param reportRepository   report-by-code lookup for the track flow.
     */
    public UssdReportService(ReportService reportService,
                             IssueCategoryRepository categoryRepository,
                             ReportRepository reportRepository) {
        this.reportService = reportService;
        this.categoryRepository = categoryRepository;
        this.reportRepository = reportRepository;
    }

    /** {@inheritDoc} */
    @Override
    public List<UssdCategoryOption> topCategories(int max) {
        if (max <= 0) {
            return List.of();
        }
        // Active categories only (the picker never offers a retired category), newest page of the menu size.
        return categoryRepository.findByActiveTrue(PageRequest.of(0, max)).getContent().stream()
                .map(c -> new UssdCategoryOption(c.getPublicId(), c.getName()))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public String fileFromUssd(UUID reporterAccountId, UUID categoryPublicId, UUID wardPublicId, String text) {
        // Build the standard file request from the USSD draft: no GPS, no attachments, not anonymous, no
        // custom visibility (the category default applies). The title is a short prefix of the description so
        // the citizen sees something meaningful in queues without a second USSD prompt. ReportService applies
        // the category/ward existence checks, the SLA, the ticket-code sequence, and the routing outbox event
        // — the SAME path as a web filing (DRY); the civic-integrity fence holds (no token on this path, D18).
        String description = text == null ? "" : text.trim();
        FileReportDto request = new FileReportDto(
                categoryPublicId,
                titleFrom(description),
                description,
                wardPublicId,
                null,            // latitude — not collected over USSD
                null,            // longitude — not collected over USSD
                null,            // visibility — category default applies
                false,           // anonymous — USSD filing is identity-linked (T1 account)
                null);           // attachmentRefs — none over USSD
        ReportDto filed = reportService.fileReport(reporterAccountId, request);
        return filed.code();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UssdReportStatus> trackByTicket(String ticketCode) {
        if (ticketCode == null || ticketCode.isBlank()) {
            return Optional.empty();
        }
        // Codes are minted upper-case (TAR-YYYY-NNNNNN); normalise the typed input so case never misses.
        String code = ticketCode.trim().toUpperCase(Locale.ROOT);
        return reportRepository.findByCode(code)
                .map(this::toStatus);
    }

    /** Maps a report to the minimal PII-free USSD status view (code + status label). */
    private UssdReportStatus toStatus(Report report) {
        return new UssdReportStatus(report.getCode(), report.getStatus().name());
    }

    /**
     * Synthesises a short, non-blank report title from the citizen's free text (the file path requires a
     * non-blank title, but USSD only captures one free-text field). Trims to {@link #TITLE_MAX} on a word-ish
     * boundary; a blank description (rejected downstream anyway) falls back to the raw text.
     *
     * @param description the citizen's free text (already trimmed).
     * @return a title ≤ {@link #TITLE_MAX} chars.
     */
    private static String titleFrom(String description) {
        if (description.length() <= TITLE_MAX) {
            return description;
        }
        return description.substring(0, TITLE_MAX).trim();
    }
}
