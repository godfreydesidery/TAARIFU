package com.taarifu.engagement.domain.model.enums;

/**
 * Lifecycle state of a public Q&A {@link com.taarifu.engagement.domain.model.Question} to a
 * representative (PRD §9.1 Question, §12.2 M10).
 *
 * <p>Responsibility: the Q&A state machine {@code OPEN → ANSWERED | DECLINED | MODERATED}
 * (PRD §12.2). A question starts {@code OPEN}; the targeted representative may answer
 * ({@code ANSWERED}, UC-E10) or decline ({@code DECLINED}); a moderator may remove it from public
 * view ({@code MODERATED}). Only {@code OPEN}/{@code ANSWERED} questions are publicly listed.</p>
 */
public enum QuestionStatus {

    /** Awaiting the targeted representative's answer (UC-E09). Publicly visible. */
    OPEN,

    /** The targeted representative published an answer (UC-E10). Publicly visible. */
    ANSWERED,

    /** The targeted representative declined to answer. */
    DECLINED,

    /** A moderator removed the question from public view (PRD §18 trust & safety). */
    MODERATED
}
