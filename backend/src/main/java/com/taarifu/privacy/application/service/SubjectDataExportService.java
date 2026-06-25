package com.taarifu.privacy.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.privacy.api.SubjectExportContributor;
import com.taarifu.privacy.api.dto.SubjectDataExport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles a data-subject ACCESS export by composing every registered {@link SubjectExportContributor}
 * (PRD §18 PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: the registry + aggregator. Spring injects <b>all</b> {@link SubjectExportContributor}
 * beans (identity, consents, and — as each CENTRAL-NEED contributor ships — reports/signatures/ratings/…); for
 * one subject this service calls each contributor read-only and builds the {@link SubjectDataExport} keyed by
 * {@code section()}. It reaches no sibling's internals — the whole point of the SPI (ARCHITECTURE §3.2,
 * ADR-0013). The export act is audited ({@link AuditEventType#PRIVACY_DSR_EXPORTED}).</p>
 *
 * <p><b>Resilience:</b> one contributor failing must not fail the whole export (a partial export is more useful
 * to the subject than none, and PDPA fulfilment should not hinge on one module's transient fault). A failing
 * contributor is logged (no PII) and its section omitted; the rest compose. WHY a {@code section}-keyed map and
 * not a fixed DTO: contributors are added incrementally (CENTRAL NEEDS) without changing this service.</p>
 *
 * <p><b>🔒 Data-minimisation:</b> each contributor returns the subject's own minimised data; the national/voter
 * ID number is excluded by default (ADR-0016 §4). The caller binds the subject to the authenticated principal —
 * a subject only ever exports their own data; an ADMIN/ROOT export runs against a tracked DSR.</p>
 */
@Service
public class SubjectDataExportService {

    private static final Logger log = LoggerFactory.getLogger(SubjectDataExportService.class);

    private final List<SubjectExportContributor> contributors;
    private final AuditEventService audit;
    private final ClockPort clock;

    /**
     * @param contributors all registered export contributors (Spring injects every bean; may be empty at the
     *                     very first increment, then grows as CENTRAL-NEED contributors land — additive).
     * @param audit        append-only audit writer (records the export act — L-1).
     * @param clock        time source for the export timestamp (testable).
     */
    public SubjectDataExportService(List<SubjectExportContributor> contributors,
                                    AuditEventService audit, ClockPort clock) {
        this.contributors = contributors;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Builds the subject's full ACCESS export from every contributor.
     *
     * @param subjectPublicId the account whose data to export.
     * @param actorPublicId   the principal running the export (the subject self-service, or an ADMIN/ROOT on a
     *                        tracked DSR) — used for the audit attribution.
     * @return the composed export (never {@code null}; {@code sections} may be empty if no module contributes).
     */
    @Transactional(readOnly = true)
    public SubjectDataExport export(UUID subjectPublicId, UUID actorPublicId) {
        Map<String, Object> sections = new LinkedHashMap<>();
        for (SubjectExportContributor contributor : contributors) {
            try {
                Object slice = contributor.contribute(subjectPublicId);
                if (slice != null) {
                    sections.put(contributor.section(), slice);
                }
            } catch (RuntimeException ex) {
                // Never let one module's fault sink the whole export. Log the section + class only — never the
                // subject id or any payload (PII redaction, PRD §18). The section is simply omitted.
                log.warn("Export contributor '{}' ({}) failed; omitting its section",
                        safeSection(contributor), contributor.getClass().getSimpleName());
            }
        }

        audit.record(AuditEvent.Builder
                .of(AuditEventType.PRIVACY_DSR_EXPORTED, AuditOutcome.SUCCESS)
                .actor(actorPublicId).subject(subjectPublicId)
                .reason("sections=" + sections.size())
                .build());

        return new SubjectDataExport(subjectPublicId, clock.now(), sections);
    }

    /** Reads a contributor's section name defensively (a broken {@code section()} must not mask the real fault). */
    private static String safeSection(SubjectExportContributor contributor) {
        try {
            return contributor.section();
        } catch (RuntimeException e) {
            return "<unknown>";
        }
    }
}
