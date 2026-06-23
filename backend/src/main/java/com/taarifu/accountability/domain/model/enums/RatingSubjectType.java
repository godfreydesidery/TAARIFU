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
 * projects module) which this module must not import (module-boundary isolation). The pair is resolved
 * through those modules' public APIs at the wiring step; here it is an opaque, validated public id
 * (// TODO(wiring)).</p>
 */
public enum RatingSubjectType {

    /** A representative (Mbunge/Diwani) — the binding US-6.2 case; rating gated at T3 + electoral scope. */
    REPRESENTATIVE,

    /** A public office/institution. */
    OFFICE,

    /** A development project. */
    PROJECT
}
