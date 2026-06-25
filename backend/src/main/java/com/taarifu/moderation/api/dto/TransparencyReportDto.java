package com.taarifu.moderation.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The moderation <b>transparency report</b> over a time window (PRD §18, §25 transparency reporting,
 * M-Phase 3; ADR-0018).
 *
 * <p>Responsibility: the PII-free, aggregate accountability artefact the platform publishes on its
 * moderation activity — action mix, appeal outcomes, flag volume by reason, and the auto-vs-manual split. It
 * is built entirely from <b>counts keyed on codes/enums</b> over moderation's own append-only tables
 * ({@code moderation_action} is immutable — V41 — so the figures are tamper-evident). It carries <b>no
 * subject id, no author, no flagger, no moderator identity, no content body, and no precise location</b>
 * (data minimisation, §18) so it is safe to publish (M-Phase 3 P3).</p>
 *
 * @param from              inclusive window start (UTC).
 * @param to                exclusive window end (UTC).
 * @param totalFlags        total flags raised in the window (the abuse-report-rate numerator).
 * @param totalActions      total moderation actions taken in the window.
 * @param totalAppeals      total appeals filed in the window.
 * @param actionsByType     count of moderation actions by type (APPROVE/HIDE/REMOVE/WARN/SUSPEND/VERIFY_REQUEST).
 * @param appealsByOutcome  count of appeals by status (OPEN/UPHELD/OVERTURNED) — the fairness signal.
 * @param flagsByReason     count of flags by reason (ABUSE/SPAM/PII/HARASSMENT/MISINFORMATION/OTHER).
 * @param itemsByAssistMode count of queue items by origin — {@code AUTO_ASSISTED} vs {@code MANUAL} (US-12.3 split).
 */
public record TransparencyReportDto(
        Instant from,
        Instant to,
        long totalFlags,
        long totalActions,
        long totalAppeals,
        List<TransparencyCountDto> actionsByType,
        List<TransparencyCountDto> appealsByOutcome,
        List<TransparencyCountDto> flagsByReason,
        List<TransparencyCountDto> itemsByAssistMode
) {

    /**
     * One labelled, PII-free count in a transparency breakdown.
     *
     * @param key   the bucket label — an enum/code name (e.g. {@code REMOVE}, {@code OVERTURNED},
     *              {@code AUTO_ASSISTED}); never a person, location, or content.
     * @param count the number of rows in this bucket.
     */
    public record TransparencyCountDto(String key, long count) {
    }
}
