package com.taarifu.geography.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The geography module's <b>public, in-process query port</b> for resolving a friendly <b>ward (Kata) code</b>
 * to its ward public id (ADR-0013 §1; ADR-0019; PRD §9.0, §14, UC-D02). A sibling channel that must let a
 * citizen identify a ward by typing its short official administrative {@code code} — above all the feature-phone
 * {@code ussd} module's "enter a ward code" step — depends on, and calls, this interface synchronously
 * ({@code ussd → geography}), <b>without</b> importing geography's {@code domain}/{@code infrastructure}
 * (ARCHITECTURE §3.2). It is the {@code *QueryApi} shape of {@code institutions.api.RepresentativeQueryApi}.
 *
 * <p>Responsibility: answer the single question "which ward does this code identify?" so a feature-phone user
 * who cannot paste a UUID can still pin a report to a ward — the minimum pin granularity (PRD §9.0). Geography
 * owns this because it owns the administrative hierarchy and the unique {@code Location.code}; the caller treats
 * the returned {@code UUID} as opaque truth and never reaches past it (it never loads the {@code Location}).</p>
 *
 * <p>WHY a synchronous read (not an event): the citizen's next USSD keypress depends on resolving the ward
 * <i>within the same request</i> — they must pin an area before they can describe the issue — so it cannot wait
 * for an async event. This is the canonical synchronous {@code *QueryApi} case (ADR-0013 §1, rule of thumb
 * "a read the citizen's write depends on → synchronous"). The edge introduces no cycle (geography never calls
 * the channel back).</p>
 *
 * <p>WHY "empty, never throw" for an unknown code (deny-by-default; EI-3/§14): a USSD dialogue must degrade to a
 * friendly "invalid code, try again" {@code CON} line, not crash on a mistyped ward code — a fat-fingered entry
 * on a feature phone is routine, not exceptional. The caller decides the retry UX; the port stays a pure,
 * total read.</p>
 */
public interface WardCodeQueryApi {

    /**
     * Resolves a ward's official administrative {@code code} to its ward public id.
     *
     * <p>Matches a non-soft-deleted {@link com.taarifu.geography.domain.model.Location} whose
     * {@code type = WARD} and whose {@code code} equals the trimmed input. The match is
     * <b>case-insensitive</b> (a citizen may type the code in any case on a feature phone) and the input is
     * trimmed of surrounding whitespace; a {@code null}/blank input yields {@link Optional#empty()}. Only
     * {@code WARD}-typed rows match — a region/district/village code never resolves here, so the result is
     * always a true ward id at the minimum pin granularity (PRD §9.0).</p>
     *
     * @param wardCode the official ward code the citizen typed (e.g. a Kata code); case-insensitive,
     *                 surrounding whitespace ignored. {@code null}/blank resolves to empty.
     * @return the matching ward's public id, or empty if no live {@code WARD} has that code.
     */
    Optional<UUID> wardIdByCode(String wardCode);
}
