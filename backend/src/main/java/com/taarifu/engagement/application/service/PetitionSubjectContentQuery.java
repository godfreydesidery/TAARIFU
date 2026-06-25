package com.taarifu.engagement.application.service;

import com.taarifu.engagement.domain.repository.PetitionRepository;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.SubjectContentQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Engagement's implementation of the moderation {@link SubjectContentQueryApi} for
 * {@link FlagSubjectType#PETITION} subjects (ADR-0018; ADR-0013 §4c; US-12.3, UC-H05, D-Q8).
 *
 * <p>Responsibility: given a flagged petition's public id, return the petition's <b>scorable text</b> so
 * moderation's auto-assist screen ({@code AutoAssistService} via {@code SubjectContentResolver}) can run the
 * Swahili-aware {@code ContentSafety} scorer and <b>hold-and-prioritise</b> a risky item for a human. It is
 * the read-side twin of {@link PetitionSubjectAuthorQuery} (author lookup): one bean per concern, registered
 * by Spring into moderation's registry, so moderation never imports engagement's internals
 * (ARCHITECTURE §3.2; the dependency-inversion that creates no cycle — moderation owns the interface,
 * engagement provides the impl).</p>
 *
 * <p>WHY the title <i>and</i> body are scored (concatenated): a petition's harmful content can live in either
 * field — the headline citizens see in discovery or the longer ask — so both are surfaced to the screen. The
 * fields are joined with a newline so the scorer's lexicon/PII regexes treat them as one document without
 * tokens bleeding across the boundary.</p>
 *
 * <p><b>🔒 Assist only, transient, no PII leak (D-Q8, R21, PRD §18, PDPA).</b> The returned text is the
 * petition's own public ask — never another user's PII this module happens to hold (the creator's id is NOT
 * included). Moderation hands the text straight to the scorer inside the triage transaction and never persists
 * or logs it (see {@link SubjectContentQueryApi}). The screen can only raise a queue item for a human; it has
 * no path to a takedown. An absent/soft-deleted petition resolves to {@link Optional#empty()} — the screen is
 * then skipped and the flagged item still goes to a human (the EI-18 human-pipeline floor).</p>
 */
@Service
@Transactional(readOnly = true)
public class PetitionSubjectContentQuery implements SubjectContentQueryApi {

    private final PetitionRepository petitionRepository;

    /**
     * @param petitionRepository petition persistence port (content lookup by public id).
     */
    public PetitionSubjectContentQuery(PetitionRepository petitionRepository) {
        this.petitionRepository = petitionRepository;
    }

    /** {@inheritDoc} */
    @Override
    public FlagSubjectType subjectType() {
        return FlagSubjectType.PETITION;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the petition's {@code title} + {@code body} joined as one scorable document, or empty for a
     * non-existent / soft-deleted petition (screen skipped → the flagged item still reaches a human).</p>
     */
    @Override
    public Optional<String> contentTextOf(UUID subjectId) {
        return petitionRepository.findByPublicId(subjectId)
                // title + body only — the public ask; never the creator id or any PII (data minimisation).
                .map(p -> p.getTitle() + "\n" + p.getBody());
    }
}
