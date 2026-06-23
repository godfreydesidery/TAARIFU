package com.taarifu.common.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;

/**
 * Generates human-readable display codes from <b>database sequences</b> for
 * {@link com.taarifu.common.domain.model.BaseCodedEntity} subtypes (ARCHITECTURE.md §4.2, ADR-0006).
 *
 * <p>Responsibility: produces gap-tolerant, race-safe codes such as {@code TAR-2026-000123} by
 * drawing the next value from a Postgres sequence — <b>never</b> {@code max(id)+1} (which races and
 * corrupts under concurrency). The sequence itself is created by the owning module's Flyway migration
 * (not here), keeping schema ownership with Flyway (ADR-0005).</p>
 *
 * <p>WHY {@code REQUIRES_NEW}: a sequence advance must <b>not</b> roll back with the caller's
 * business transaction. Sequences are intentionally gap-tolerant — a rolled-back report still
 * "burned" its number, and that is correct (numbers are non-reused identifiers, never a count). This
 * also prevents two concurrent creators from ever receiving the same code.</p>
 *
 * <p>No coded entity exists in this foundation increment; this component establishes the pattern the
 * reporting increment ({@code Report.code}) will use. The {@code sequenceName} is supplied by the
 * caller so each entity type owns its own sequence.</p>
 */
@Component
public class CodeGenerator {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Builds a year-scoped, zero-padded code of the form {@code <prefix>-<year>-<NNNNNN>}.
     *
     * @param prefix       the human prefix (e.g. {@code "TAR"} for a report ticket).
     * @param sequenceName the Postgres sequence name created in the owning module's migration.
     * @param padWidth     zero-pad width for the numeric portion (e.g. {@code 6} → {@code 000123}).
     * @return the generated code; unique because the sequence guarantees a fresh value.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextCode(String prefix, String sequenceName, int padWidth) {
        long next = nextSequenceValue(sequenceName);
        return "%s-%d-%0" + padWidth + "d".formatted(prefix, Year.now().getValue(), next);
    }

    /**
     * Draws the next raw value from a Postgres sequence.
     *
     * <p>WHY a native query with the sequence name validated against an allow-list pattern: sequence
     * names cannot be JDBC bind parameters, so we interpolate — but only after asserting the name is a
     * safe identifier, to keep this injection-proof.</p>
     *
     * @param sequenceName the sequence to advance.
     * @return the next sequence value.
     */
    long nextSequenceValue(String sequenceName) {
        if (!sequenceName.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("Unsafe sequence name: " + sequenceName);
        }
        Object value = entityManager
                .createNativeQuery("SELECT nextval('" + sequenceName + "')")
                .getSingleResult();
        return ((Number) value).longValue();
    }
}
