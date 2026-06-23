package com.taarifu.institutions.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The institutions module's <b>public, in-process query port</b> for resolving a representative's identity
 * and electoral seat (ADR-0013 §1; D13). A sibling that gates a binding action on the subject
 * representative (accountability rate-rep, engagement petition-against-a-rep) depends on, and calls, this
 * interface — never institutions' {@code domain}/{@code infrastructure} (ARCHITECTURE §3.2).
 *
 * <p>Responsibility: answer "does this representative exist, and what constituency (Jimbo) do they hold?"
 * so the caller can resolve the binding-action electoral scope. The caller treats the result as opaque
 * truth and does not reach past it.</p>
 */
public interface RepresentativeQueryApi {

    /**
     * Resolves the representative's constituency public id (the electoral unit a citizen must be an elector
     * of to rate/petition against them, D13).
     *
     * <p>A {@code CONSTITUENCY}-mandate MP has a constituency; a {@code COUNCILLOR_WARD} councillor,
     * {@code SPECIAL_SEATS} (Viti Maalum) or {@code NOMINATED} representative has <b>none</b> — for those,
     * the result is {@link Optional#empty()} and the caller decides the scope rule for a constituency-less
     * subject (typically: no constituency electoral gate applies). The method is read-only.</p>
     *
     * @param representativePublicId the representative's public id.
     * @return the constituency public id if the representative holds a constituency seat, else empty.
     * @throws com.taarifu.common.error.ApiException with {@code NOT_FOUND} if no such representative exists.
     */
    Optional<UUID> constituencyOf(UUID representativePublicId);
}
