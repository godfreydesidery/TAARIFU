package com.taarifu.moderation.api;

/**
 * The kind of content a {@link com.taarifu.moderation.domain.model.Flag} (and the
 * {@link com.taarifu.moderation.domain.model.ModerationItem} it raises) targets (PRD §18, M12).
 *
 * <p>Responsibility: names the moderatable content surfaces so a flag/queue item is self-describing
 * without this module importing any other module. The concrete record is referenced only by
 * {@code (subjectType, subjectId UUID)}; the owning module resolves it to a record/author via the published
 * {@link SubjectAuthorQueryApi} (ADR-0013 §4c).</p>
 *
 * <p>WHY this enum lives in {@code moderation.api} (not {@code domain.model.enums}): it is part of
 * moderation's <b>published contract</b> — it already appears in the public {@code FlagContentRequest}/
 * {@code FlagDto} DTOs and is the registry key of {@link SubjectAuthorQueryApi}, so content-owning modules
 * legitimately reference it through the api package without depending on moderation's internals
 * (ARCHITECTURE §3.2; ADR-0013 §1 — ports expose only public enums/DTOs/UUIDs).</p>
 *
 * <p>WHY a string-persisted enum (not a free-text type column): it keeps the flag surface a closed,
 * reviewable set that the queue can prioritise and that a DB {@code CHECK} guards, while staying additive
 * — new content types append a value (never repurpose one; clients/SOC tooling branch on it).</p>
 */
public enum FlagSubjectType {

    /** A citizen-filed issue report / case (reporting module). */
    REPORT,

    /** A comment on any entity (engagement module). */
    COMMENT,

    /** A published announcement (communications module). */
    ANNOUNCEMENT,

    /** A petition (engagement module). */
    PETITION,

    /** A Q&amp;A question (engagement module). */
    QUESTION,

    /** A user/organisation profile (identity module). */
    PROFILE,

    /** A representative rating/review (accountability module). */
    RATING,

    /** Any other moderatable surface not yet enumerated (keeps the flag path open). */
    OTHER
}
