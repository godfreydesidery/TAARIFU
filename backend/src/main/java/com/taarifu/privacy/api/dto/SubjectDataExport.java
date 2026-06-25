package com.taarifu.privacy.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The composed data-subject ACCESS export — every module's contributed section keyed by section name
 * (PRD §18 PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: the single envelope returned to a subject (or to an operator on a tracked DSR) carrying
 * their personal data, assembled by the {@code SubjectExportContributorRegistry} from each registered
 * {@link com.taarifu.privacy.api.SubjectExportContributor}. The {@link #sections} map is contributor
 * {@code section()} → that contributor's minimised slice (identity profile summary, consent ledger, reports,
 * signatures, …). A module with no data for the subject contributes nothing, so its key is simply absent.</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18):</b> the export is the one place decrypted PII could surface; each
 * contributor is responsible for returning the minimum, and the national/voter ID <b>number</b> is excluded by
 * default (ADR-0016 §4 — return ID type + verification state, not the number, pending Legal sign-off). The
 * controller binds the subject to the authenticated principal — a subject only ever exports their own data.</p>
 *
 * @param subjectPublicId the account the export is for.
 * @param generatedAt     when the export was assembled (UTC).
 * @param sections        section-name → that section's minimised data slice (never {@code null}; may be empty).
 */
public record SubjectDataExport(
        UUID subjectPublicId,
        Instant generatedAt,
        Map<String, Object> sections) {
}
