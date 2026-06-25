package com.taarifu.moderation.application.service;

import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.SubjectContentQueryApi;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dispatches a flagged {@code (subjectType, subjectId)} to the owning module's published
 * {@link SubjectContentQueryApi}, resolving the subject's <b>scorable text</b> for the auto-assist screen
 * (US-12.3, UC-H05; ADR-0018; ADR-0013 §4c).
 *
 * <p>Responsibility: hold the registry of owner content-lookup ports (one per {@link FlagSubjectType}) and
 * answer {@link #contentTextOf(FlagSubjectType, UUID)}. Owners register by implementing
 * {@link SubjectContentQueryApi} as a Spring bean and declaring their
 * {@link SubjectContentQueryApi#subjectType()}; Spring injects them all here. It mirrors
 * {@link SubjectAuthorResolver} exactly — the same registry pattern keeps moderation free of any concrete
 * content-owner import (ARCHITECTURE §3.2).</p>
 *
 * <p><b>🔒 Graceful degradation (EI-18, D-Q8).</b> A subject type with <b>no registered owner port</b> (the
 * launch reality — owners wire their content port incrementally) resolves to {@link Optional#empty()}. The
 * auto-assist caller ({@link FlagService}) then simply <b>skips the scorer</b> for that subject; the flagged
 * item still raises a queue entry for a human moderator. So the absence of a content port never blocks
 * flagging and never silences content — the human pipeline is always the floor.</p>
 *
 * <p><b>🔒 Transient text (PRD §18, PDPA).</b> The text returned here is handed straight to the scorer inside
 * the triage transaction and never persisted or logged by moderation (see {@link SubjectContentQueryApi}).</p>
 */
@Component
public class SubjectContentResolver {

    private final Map<FlagSubjectType, SubjectContentQueryApi> byType;

    /**
     * @param ports all published {@link SubjectContentQueryApi} beans (owner modules register their impl);
     *              may be empty at launch (no owner has wired its content port yet — degradation to no-screen).
     * @throws IllegalStateException if two owners claim the same {@link FlagSubjectType} (ambiguous dispatch).
     */
    public SubjectContentResolver(List<SubjectContentQueryApi> ports) {
        Map<FlagSubjectType, SubjectContentQueryApi> map = new EnumMap<>(FlagSubjectType.class);
        for (SubjectContentQueryApi port : ports) {
            SubjectContentQueryApi prior = map.putIfAbsent(port.subjectType(), port);
            if (prior != null) {
                throw new IllegalStateException(
                        "Two SubjectContentQueryApi beans claim subject type " + port.subjectType()
                                + ": " + prior.getClass().getName() + " and " + port.getClass().getName());
            }
        }
        this.byType = map;
    }

    /**
     * Resolves the scorable text of a moderatable subject, dispatching by subject type.
     *
     * @param subjectType the kind of content (the registry key).
     * @param subjectId   the content's public id.
     * @return the content body to scan, or {@link Optional#empty()} if no owner is registered for the type,
     *         the subject does not exist, or it has no scannable text — in every empty case the auto-assist
     *         screen is skipped and the item is left to a human (EI-18).
     */
    public Optional<String> contentTextOf(FlagSubjectType subjectType, UUID subjectId) {
        SubjectContentQueryApi port = byType.get(subjectType);
        if (port == null || subjectId == null) {
            return Optional.empty();
        }
        return port.contentTextOf(subjectId);
    }
}
