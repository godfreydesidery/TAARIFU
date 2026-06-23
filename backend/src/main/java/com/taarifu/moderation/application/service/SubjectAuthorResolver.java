package com.taarifu.moderation.application.service;

import com.taarifu.moderation.api.SubjectAuthorQueryApi;
import com.taarifu.moderation.api.FlagSubjectType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dispatches a flagged {@code (subjectType, subjectId)} to the owning module's published
 * {@link SubjectAuthorQueryApi}, resolving the subject's author for the D16 self-action guard (ADR-0013 §4c).
 *
 * <p>Responsibility: hold the registry of owner ports (one per {@link FlagSubjectType}) and answer
 * {@link #authorOf(FlagSubjectType, UUID)}. Owners register by implementing {@link SubjectAuthorQueryApi}
 * as a Spring bean and declaring their {@link SubjectAuthorQueryApi#subjectType()}; Spring injects them all
 * here. A subject type with no registered owner (or whose subject has no surfaced author) resolves to
 * empty — moderation then proceeds with a {@code null} author, leaving the self-action guard vacuously
 * satisfied (deny-by-default still applies to the moderator role itself).</p>
 *
 * <p>WHY a registry (not a {@code switch} importing each owner): moderation must stay free of any concrete
 * content-owner import (ARCHITECTURE §3.2). It depends only on the {@link SubjectAuthorQueryApi} interface;
 * the owners self-register, so adding a new moderatable surface needs no change here.</p>
 */
@Component
public class SubjectAuthorResolver {

    private final Map<FlagSubjectType, SubjectAuthorQueryApi> byType;

    /**
     * @param ports all published {@link SubjectAuthorQueryApi} beans (owner modules register their impl).
     * @throws IllegalStateException if two owners claim the same {@link FlagSubjectType} (ambiguous dispatch).
     */
    public SubjectAuthorResolver(List<SubjectAuthorQueryApi> ports) {
        Map<FlagSubjectType, SubjectAuthorQueryApi> map = new EnumMap<>(FlagSubjectType.class);
        for (SubjectAuthorQueryApi port : ports) {
            SubjectAuthorQueryApi prior = map.putIfAbsent(port.subjectType(), port);
            if (prior != null) {
                throw new IllegalStateException(
                        "Two SubjectAuthorQueryApi beans claim subject type " + port.subjectType()
                                + ": " + prior.getClass().getName() + " and " + port.getClass().getName());
            }
        }
        this.byType = map;
    }

    /**
     * Resolves the author of a moderatable subject to their account public id, dispatching by subject type.
     *
     * @param subjectType the kind of content (the registry key).
     * @param subjectId   the content's public id.
     * @return the author's account public id, or {@link Optional#empty()} if no owner is registered for the
     *         type, the subject does not exist, or it has no surfaced author (e.g. anonymous report).
     */
    public Optional<UUID> authorOf(FlagSubjectType subjectType, UUID subjectId) {
        SubjectAuthorQueryApi port = byType.get(subjectType);
        if (port == null || subjectId == null) {
            return Optional.empty();
        }
        return port.authorOf(subjectId);
    }
}
