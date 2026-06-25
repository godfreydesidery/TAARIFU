package com.taarifu.accountability.application.service;

import com.taarifu.accountability.domain.repository.RatingRepository;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.SubjectContentQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Accountability's implementation of the moderation {@link SubjectContentQueryApi} for
 * {@link FlagSubjectType#RATING} subjects (PRD §12 US-12.3, UC-H05, EI-18, D-Q8; ADR-0018; ADR-0013 §4c).
 *
 * <p>Responsibility: when a citizen flags a representative rating, moderation holds only the opaque
 * {@code (RATING, ratingPublicId)} and must <b>not</b> import accountability's internals. This bean lets
 * moderation resolve that flagged rating to its <b>scorable free-text comment</b> so the auto-assist
 * screen can run (e.g. catch profanity/PII/spam in a rating comment that has a right-of-reply), without a
 * boundary breach. It is the content-lookup twin of the author-lookup port pattern (mirrors
 * {@code reporting.ReportSubjectAuthorQuery}); accountability registers exactly one
 * {@link FlagSubjectType} ({@link FlagSubjectType#RATING}) and moderation's {@code SubjectContentResolver}
 * auto-discovers this Spring bean by that key.</p>
 *
 * <h3>What is returned — the rating's free-text comment only</h3>
 * <p>A {@code Rating}'s only moderatable free text is its optional {@code comment} (US-6.2 — the comment is
 * "moderated downstream"). The numeric score and the period are not scannable content, so the only thing
 * surfaced here is the comment. An empty/absent comment (a score-only rating) yields
 * {@link Optional#empty()} → the auto-assist screen is skipped for that subject and the flagged item still
 * goes to a human moderator (the human-pipeline floor — EI-18).</p>
 *
 * <p><b>🔒 Transient, no PII leak (PRD §18, PDPA).</b> The returned comment is handled transiently by
 * moderation: scored inside the triage transaction and never persisted on the queue item, in an event, or
 * in a log (the queue item records only the signal label + confidence). This bean returns <b>only the
 * comment under review</b> — never the rater's identity, the rated subject, or any other PII the row holds.
 * It produces input to a screen, never an action: it can cause the rating to be <i>held for human
 * review</i> and prioritised, never approved/hidden/removed (assist-only — D-Q8, R21).</p>
 *
 * <p>WHY this is a sanctioned cross-module call: implementing {@code moderation.api.SubjectContentQueryApi}
 * is a feature→foundation {@code api → api} dependency (moderation owns the interface; accountability
 * provides the impl — dependency inversion, no cycle). Accountability imports no moderation internals, and
 * moderation imports no accountability internals (ADR-0013 §1/§4c; ModuleBoundaryTest stays GREEN).</p>
 */
@Service
@Transactional(readOnly = true)
public class RatingSubjectContentQuery implements SubjectContentQueryApi {

    private final RatingRepository ratingRepository;

    /**
     * @param ratingRepository rating persistence port (comment lookup by public id).
     */
    public RatingSubjectContentQuery(RatingRepository ratingRepository) {
        this.ratingRepository = ratingRepository;
    }

    /** {@inheritDoc} */
    @Override
    public FlagSubjectType subjectType() {
        return FlagSubjectType.RATING;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resolves a flagged rating to its free-text comment — the only scorable content on a rating. Returns
     * {@link Optional#empty()} for a missing rating or a score-only rating with a blank/absent comment, in
     * which case the auto-assist screen is skipped and the item is left to a human (EI-18).</p>
     */
    @Override
    public Optional<String> contentTextOf(UUID subjectId) {
        return ratingRepository.findByPublicId(subjectId)
                .map(rating -> rating.getComment())
                .filter(comment -> !comment.isBlank());
    }
}
