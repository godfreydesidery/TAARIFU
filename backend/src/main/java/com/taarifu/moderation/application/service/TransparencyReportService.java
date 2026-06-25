package com.taarifu.moderation.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.moderation.api.dto.TransparencyReportDto;
import com.taarifu.moderation.api.dto.TransparencyReportDto.TransparencyCountDto;
import com.taarifu.moderation.domain.repository.AppealRepository;
import com.taarifu.moderation.domain.repository.FlagRepository;
import com.taarifu.moderation.domain.repository.ModerationActionRepository;
import com.taarifu.moderation.domain.repository.ModerationItemRepository;
import com.taarifu.moderation.domain.repository.projection.CountByKeyProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Read-only application service powering the moderation <b>transparency report</b> (PRD §18, §25 transparency
 * reporting, M-Phase 3; ADR-0018).
 *
 * <p>Responsibility: aggregates moderation's own tables over a window into the PII-free
 * {@link TransparencyReportDto} — action mix, appeal outcomes, flag volume by reason, and the auto-vs-manual
 * split. It owns the read transaction and the <b>window defaulting</b> (a {@code null} window resolves to the
 * last 30 days, matching the analytics-service convention) so every call behaves identically (DRY).</p>
 *
 * <p><b>WHY it reads the operational moderation tables, not the analytics read model:</b> the transparency
 * report is a <i>moderation-owned</i> accountability artefact over moderation's own append-only tables
 * ({@code moderation_action} is immutable — V41 — so the counts are tamper-evident). Reading analytics would
 * couple moderation to a sibling for its own published numbers; the operational tables are the authoritative
 * source here (the analytics dashboards remain the cross-cutting KPI lens — ADR-0018 §4).</p>
 *
 * <p><b>🔒 PII-free (data minimisation, §18; PDPA):</b> every figure is an aggregate count keyed on a
 * code/enum — there is no subject id, author, flagger, moderator identity, content body, or precise location
 * anywhere in the result, so it is safe to publish (M-Phase 3 P3). The {@link ClockPort} makes "now"
 * injectable so window defaulting is deterministic in tests.</p>
 */
@Service
@Transactional(readOnly = true)
public class TransparencyReportService {

    /** Default look-back window when the caller supplies no {@code from} (30 days). */
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final FlagRepository flagRepository;
    private final ModerationActionRepository actionRepository;
    private final AppealRepository appealRepository;
    private final ModerationItemRepository itemRepository;
    private final ClockPort clock;

    /**
     * @param flagRepository   flag counts (by reason; abuse-report numerator).
     * @param actionRepository action counts (by type; action mix over the append-only log).
     * @param appealRepository appeal counts (by outcome; fairness signal).
     * @param itemRepository   item counts (auto-vs-manual split; US-12.3).
     * @param clock            injectable "now" for window defaulting (testability).
     */
    public TransparencyReportService(FlagRepository flagRepository,
                                     ModerationActionRepository actionRepository,
                                     AppealRepository appealRepository,
                                     ModerationItemRepository itemRepository,
                                     ClockPort clock) {
        this.flagRepository = flagRepository;
        this.actionRepository = actionRepository;
        this.appealRepository = appealRepository;
        this.itemRepository = itemRepository;
        this.clock = clock;
    }

    /**
     * Builds the transparency report over the resolved window (§25).
     *
     * @param from optional inclusive window start (UTC); defaults to 30 days before {@code to}.
     * @param to   optional exclusive window end (UTC); defaults to now.
     * @return the PII-free {@link TransparencyReportDto}.
     */
    public TransparencyReportDto report(Instant from, Instant to) {
        Window w = resolveWindow(from, to);
        return new TransparencyReportDto(
                w.from(), w.to(),
                flagRepository.countInWindow(w.from(), w.to()),
                actionRepository.countInWindow(w.from(), w.to()),
                appealRepository.countInWindow(w.from(), w.to()),
                toCounts(actionRepository.countByTypeInWindow(w.from(), w.to())),
                toCounts(appealRepository.countByStatusInWindow(w.from(), w.to())),
                toCounts(flagRepository.countByReasonInWindow(w.from(), w.to())),
                toCounts(itemRepository.countByAssistModeInWindow(w.from(), w.to())));
    }

    /** Maps the shared count projection into the public transparency count DTO (key is always a code/enum). */
    private static List<TransparencyCountDto> toCounts(List<CountByKeyProjection> rows) {
        return rows.stream().map(r -> new TransparencyCountDto(r.getKey(), r.getCount())).toList();
    }

    /**
     * Resolves an optional {@code from}/{@code to} into a concrete window: {@code to} defaults to now,
     * {@code from} defaults to {@code to - 30 days}. A caller-supplied {@code from > to} is normalised by
     * swapping, so a transposed range never returns an empty/negative window (the analytics-service rule).
     */
    private Window resolveWindow(Instant from, Instant to) {
        Instant end = to != null ? to : clock.now();
        Instant start = from != null ? from : end.minus(DEFAULT_WINDOW);
        return start.isAfter(end) ? new Window(end, start) : new Window(start, end);
    }

    /** A resolved, inclusive-start/exclusive-end time window. */
    private record Window(Instant from, Instant to) {
    }
}
