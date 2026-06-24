package com.taarifu.accountability.domain.model.enums;

/**
 * What a {@code Rating} is about — the kind of subject being rated (PRD §10 Epic M6, US-6.2; §23 fence).
 *
 * <p>Responsibility: discriminates the polymorphic {@code (subjectType, subjectId)} target of a rating
 * so the same binding, one-per-person, period-scoped rating mechanism serves representatives, offices,
 * and projects. Stored as a {@code @Enumerated(STRING)} (stable name, never ordinal).</p>
 *
 * <p>WHY the subject is referenced by {@code subjectType + UUID} rather than a real FK: the rated
 * subjects live in <b>other modules</b> (representatives/offices in institutions, projects in the
 * projects module) which this module must not import (module-boundary isolation). A
 * {@link #REPRESENTATIVE} subject is resolved through institutions' published {@code RepresentativeQueryApi}
 * (existence + electoral scope) in {@code RatingService}; {@link #OFFICE}/{@link #PROJECT} have no owning
 * module/port yet, so their existence is not yet validated.</p>
 */
public enum RatingSubjectType {

    /** A representative (Mbunge/Diwani) — the binding US-6.2 case; rating gated at T3 + electoral scope. */
    REPRESENTATIVE,

    /** A public office/institution. */
    OFFICE,

    /** A development project. */
    PROJECT
}
