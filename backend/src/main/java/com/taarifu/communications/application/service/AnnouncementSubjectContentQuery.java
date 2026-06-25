package com.taarifu.communications.application.service;

import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.SubjectContentQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Communications' implementation of the moderation {@link SubjectContentQueryApi} for
 * {@link FlagSubjectType#ANNOUNCEMENT} subjects (PRD §12 US-12.3, UC-H05, EI-18, D-Q8; ADR-0018; ADR-0013 §4c).
 * It surfaces a flagged announcement's <b>scorable body text</b> to moderation's auto-assist screen, without
 * moderation importing communications' internals.
 *
 * <p>Responsibility: given an announcement public id, return its content body (the citizen-authored text the
 * Swahili-aware {@code ContentSafety} scorer scans), or {@link Optional#empty()} when no such announcement
 * exists or it has no scannable text. Communications owns the {@code ANNOUNCEMENT} subject type (the only
 * moderatable surface this module owns — it has no comment/reply entity), so it publishes exactly one content
 * port for it; moderation's {@code SubjectContentResolver} auto-discovers this bean by its
 * {@link #subjectType()} and dispatches flagged announcements to it (the same registry pattern as the existing
 * {@code SubjectAuthorQueryApi} impls).</p>
 *
 * <p><b>🔒 Both languages, body only — never PII or metadata (PRD §18, PDPA, ADR-0018).</b> The returned text
 * is the bilingual body ({@code bodySw} + {@code bodyEn} when present) — what a moderator would read — so the
 * scorer screens Swahili and English (and code-switching) alike. The <b>title is deliberately excluded</b>
 * (it is a headline, not the moderatable body) and so are the author id, areas, channels, and schedule — the
 * port surfaces only the content under review, never another field this module happens to hold. Moderation
 * handles the text <b>transiently</b>: it is scored inside the triage transaction and never persisted on the
 * queue item, in an event, or in a log (see {@link SubjectContentQueryApi}).</p>
 *
 * <p><b>🔒 Assist only (D-Q8, R21).</b> This port returns <i>input to a screen</i>, never an action. The text
 * it surfaces can only cause a flagged announcement's queue item to be held for human review and prioritised —
 * it can never approve, hide, remove, or unpublish. The human pipeline is always the floor; if the scorer is
 * the degraded stub or returns nothing, the flagged announcement still goes to a human moderator (EI-18).</p>
 */
@Service
@Transactional(readOnly = true)
public class AnnouncementSubjectContentQuery implements SubjectContentQueryApi {

    /** Separates the SW and EN bodies in the combined scorable text so both registers are screened. */
    private static final String LANGUAGE_SEPARATOR = "\n";

    private final AnnouncementRepository announcementRepository;

    /**
     * @param announcementRepository announcement persistence port (body lookup by public id).
     */
    public AnnouncementSubjectContentQuery(AnnouncementRepository announcementRepository) {
        this.announcementRepository = announcementRepository;
    }

    /** {@inheritDoc} */
    @Override
    public FlagSubjectType subjectType() {
        return FlagSubjectType.ANNOUNCEMENT;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the announcement's bilingual body (Swahili always; English appended when present) as the
     * scorable text, or empty if the announcement does not exist (soft-deleted rows are excluded by the
     * entity's {@code @SQLRestriction}) or carries no body. Empty means moderation skips the auto-assist
     * screen for this subject and the flagged item still reaches a human moderator (EI-18).</p>
     */
    @Override
    public Optional<String> contentTextOf(UUID subjectId) {
        return announcementRepository.findByPublicId(subjectId)
                .map(this::scorableText)
                .filter(text -> !text.isBlank());
    }

    /**
     * Combines the announcement's Swahili and (optional) English bodies into one scorable string — the body
     * only, never the title or any other field (the title is a headline, not the moderatable body).
     *
     * @param a the announcement.
     * @return the bilingual body text to scan (may be blank if both bodies are empty, which the caller filters).
     */
    private String scorableText(Announcement a) {
        String sw = a.getBodySw() == null ? "" : a.getBodySw();
        String en = a.getBodyEn();
        if (en == null || en.isBlank()) {
            return sw;
        }
        return sw.isBlank() ? en : sw + LANGUAGE_SEPARATOR + en;
    }
}
