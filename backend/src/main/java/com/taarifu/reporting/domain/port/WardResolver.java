package com.taarifu.reporting.domain.port;

import java.util.UUID;

/**
 * The reporting module's port for resolving a citizen-picked ward to the administrative/electoral anchor
 * a report needs (PRD §9.0; ARCHITECTURE.md §3.2 cross-module via ports).
 *
 * <p>Responsibility: turns a ward {@code publicId} into the {@link Resolution} the file-report flow stores
 * on the {@code Report} — confirming the ward exists (and is genuinely a WARD, the minimum pin
 * granularity) and supplying the constituency in effect for it. The reporting service depends on this
 * <b>abstraction</b>, not on the geography module directly, so it stays unit-testable with a stub and the
 * module boundary holds (SOLID/DIP, CLAUDE.md §3).</p>
 *
 * <p>The production adapter (in {@code reporting.infrastructure}) delegates to geography's public query
 * service, which owns the effective-dated ward→constituency bridge — reporting never reaches into
 * geography's tables (ARCHITECTURE.md §4.3).</p>
 */
public interface WardResolver {

    /**
     * Resolves a ward to its administrative/electoral anchor.
     *
     * @param wardPublicId the citizen-picked ward {@code publicId}.
     * @return the resolution (the same ward id, echoed for confirmation, plus the effective constituency).
     * @throws com.taarifu.common.error.ResourceNotFoundException if the id is not a known WARD.
     */
    Resolution resolveWard(UUID wardPublicId);

    /**
     * The resolved anchor for a report.
     *
     * @param wardPublicId         the confirmed ward {@code publicId} (minimum pin granularity).
     * @param constituencyPublicId the constituency {@code publicId} in effect for the ward, or
     *                             {@code null} if the ward has no current electoral mapping.
     */
    record Resolution(UUID wardPublicId, UUID constituencyPublicId) {
    }
}
