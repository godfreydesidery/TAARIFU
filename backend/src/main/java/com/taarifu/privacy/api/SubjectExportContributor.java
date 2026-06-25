package com.taarifu.privacy.api;

import java.util.UUID;

/**
 * The SPI an owning module implements to contribute <b>one section</b> of a data-subject ACCESS export
 * (PRD §18 PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: lets the privacy module assemble a subject's full export <b>without</b> importing any
 * sibling's {@code domain}/{@code repository} (ARCHITECTURE §3.2). Each module that holds personal data about
 * a subject (identity profile, reports filed, petition signatures, ratings, notifications, consents, …)
 * implements this in its <b>own</b> {@code application.service} layer and declares it a Spring bean; the
 * privacy {@code SubjectExportContributorRegistry} injects every {@link SubjectExportContributor} and composes
 * the export by {@link #section()}. This is the published-port pattern (ADR-0013) inverted into a registry —
 * the same shape as the {@code SubjectAuthorQueryApi} dispatch — so privacy depends only on this interface and
 * each owner stays the single reader of its own tables.</p>
 *
 * <p>WHY a registry SPI rather than privacy calling each {@code *QueryApi} directly: it keeps privacy free of a
 * compile dependency on every feature module (privacy is foundation; it must not depend on {@code reporting}/
 * {@code engagement}/…); owners opt in by declaring a bean, and a module with no contributor simply contributes
 * nothing (additive — partial coverage is safe while the CENTRAL-NEED contributors are still being built).</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18, PDPA):</b> a contributor returns the subject's <b>own</b> data only, and
 * each owner decides its minimised shape. Above all, a contributor MUST NOT return the decrypted national/voter
 * ID number by default (ADR-0016 §4 — an export must not become a fresh PII copy in transit); return the ID
 * <i>type</i> + verification state, not the number, unless Legal mandates otherwise (a CENTRAL NEED). The
 * privacy controller that drives the export is method-secured and binds the subject to the authenticated
 * principal; this SPI carries no authorization of its own.</p>
 */
public interface SubjectExportContributor {

    /**
     * The stable section key this contributor fills in the composed export (e.g. {@code "identity"},
     * {@code "consents"}, {@code "reports"}). Unique per contributor; used as the export map key.
     *
     * @return the non-blank section key.
     */
    String section();

    /**
     * Produces this module's minimised slice of the subject's personal data, or {@code null} if the subject
     * has none in this module.
     *
     * <p>The returned object must be a JSON-serialisable DTO/record of the subject's own data — never a JPA
     * entity, never another subject's data, and (by default) never the raw national/voter ID number
     * (data minimisation, PRD §18).</p>
     *
     * @param subjectPublicId the account public id of the subject whose data to export.
     * @return the minimised export slice, or {@code null} if nothing to contribute for this subject.
     */
    Object contribute(UUID subjectPublicId);
}
