package com.taarifu.moderation.api;

import java.util.Optional;
import java.util.UUID;

/**
 * A per-owner <b>subject-content lookup port</b> that the moderation module dispatches to, to resolve a
 * flagged {@code (subjectType, subjectId)} to the content's <b>scorable text</b> for the auto-assist screen
 * (PRD §12 US-12.3, UC-H05, EI-18, D-Q8; ADR-0018; ADR-0013 §4c).
 *
 * <p>Responsibility: invert the moderation boundary safely so auto-assist can run <i>without</i> moderation
 * importing any content-owning module. Moderation holds only the opaque {@code (subjectType, subjectId)} and
 * must <b>not</b> import reporting/engagement/communications/… — so each owner module publishes an
 * implementation of THIS interface (declaring the one {@link FlagSubjectType} it serves via
 * {@link #subjectType()}), and moderation injects them as a registry keyed by subject type (the same pattern
 * as {@link SubjectAuthorQueryApi}). The owner answers "what is the text body of this record?" and moderation
 * feeds that text <b>transiently</b> to the {@code ContentSafety} scorer.</p>
 *
 * <p><b>🔒 Transient text — never persisted by moderation (PRD §18, PDPA).</b> The returned text is the
 * content body, handed straight to the scorer inside the triage transaction and <b>never</b> stored on the
 * queue item, in an event, or in a log. The queue item records only labels (signal + confidence). An owner
 * MUST return only the content actually under review — never another user's PII it happens to hold.</p>
 *
 * <p><b>🔒 Assist only (D-Q8, R21).</b> This port produces <b>input to a screen</b>, not an action. The text
 * it returns can cause a queue item to be <i>held for human review</i> and prioritised; it can never approve,
 * hide, remove, or sanction. The human pipeline is always the floor — and if <b>no</b> owner publishes a
 * content port for a subject type (the launch reality), the screen simply does not run and the flagged item
 * still goes to a human moderator (graceful degradation, EI-18).</p>
 *
 * <p>WHY this interface lives in {@code moderation.api} (not in each owner's api): moderation owns the
 * {@link FlagSubjectType} taxonomy and the dispatch, and is a <i>foundation</i> module — so a feature owner
 * (reporting/engagement) implementing it is a sanctioned feature→foundation {@code api} dependency
 * (ARCHITECTURE §3.2), with no module importing another's internals. It mirrors {@link SubjectAuthorQueryApi}
 * exactly so an owner publishes one bean per concern (author lookup, content lookup) with no new pattern.</p>
 */
public interface SubjectContentQueryApi {

    /**
     * @return the single {@link FlagSubjectType} this owner resolves (its registry key). Each owner serves
     *         exactly one subject type so moderation's dispatch is unambiguous.
     */
    FlagSubjectType subjectType();

    /**
     * Resolves the <b>scorable text</b> of a moderatable subject — the content body the auto-assist scorer
     * scans (US-12.3).
     *
     * <p>The returned text is handled <b>transiently</b> by moderation (see the type Javadoc): it is scored
     * and discarded, never persisted or logged.</p>
     *
     * @param subjectId the flagged content's public id (in the owner's module).
     * @return the content body to scan, or {@link Optional#empty()} if the subject does not exist, has no
     *         scannable text (e.g. a pure-media post — its IMAGE signal is a separate vision concern), or the
     *         owner declines to surface it. Empty means the auto-assist screen is skipped for this subject —
     *         the flagged item still goes to a human moderator (the human-pipeline floor, EI-18).
     */
    Optional<String> contentTextOf(UUID subjectId);
}
