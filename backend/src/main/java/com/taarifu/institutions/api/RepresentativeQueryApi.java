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

    /**
     * Resolves the representative's ward (Kata) public id — the electoral unit a citizen must be an elector
     * of to rate/petition against a <b>ward-tier</b> representative (a Councillor/Diwani or ward executive,
     * D13, PRD §9.0).
     *
     * <p>WHY this exists alongside {@link #constituencyOf} (F1): the electoral mapping is two-tier — a
     * {@code CONSTITUENCY}-mandate MP holds a constituency (gated via {@link #constituencyOf}), while a
     * {@code COUNCILLOR_WARD} councillor holds a <b>ward</b>, which itself carries a real geographic seat.
     * Resolving only the constituency silently skipped the gate for every councillor (a citizen anywhere
     * could rate/petition any councillor); this method lets the caller gate a ward-tier subject on the
     * citizen's electoral ward. A {@code CONSTITUENCY}-mandate MP, {@code SPECIAL_SEATS} (Viti Maalum) or
     * {@code NOMINATED} representative has <b>no ward</b> — for those the result is {@link Optional#empty()}
     * (special-seats/nominated genuinely have no geographic seat, PRD §22.6; MPs are gated by constituency
     * instead). The method is read-only.</p>
     *
     * @param representativePublicId the representative's public id.
     * @return the ward public id if the representative holds a ward seat (councillor/ward-exec), else empty.
     * @throws com.taarifu.common.error.ApiException with {@code NOT_FOUND} if no such representative exists.
     */
    Optional<UUID> wardOf(UUID representativePublicId);
}
