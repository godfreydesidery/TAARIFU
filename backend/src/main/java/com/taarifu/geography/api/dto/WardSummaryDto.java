package com.taarifu.geography.api.dto;

import java.util.UUID;

/**
 * Lean projection of a Ward (Kata) for the <b>manual ward-picker</b> surface — the district→wards
 * listing and ward search (PRD §9.0; "find my representative" front door, PRD §22.6).
 *
 * <p>Responsibility: the smallest boundary shape a client needs to render a ward choice when GPS is
 * unavailable — the ward's {@code publicId} (the value report forms / {@code ProfileLocation} /
 * find-my-rep take by hand), its display {@code name} and {@code code}, plus the human-readable
 * <b>parent council and district names</b> so a picker can disambiguate same-named wards (e.g. two
 * "Mji Mpya" wards in different councils) without a second round-trip.</p>
 *
 * <p>WHY a separate, leaner DTO than {@link WardDto} (which carries the parent <i>id</i>):
 * (1) a feature-phone-served, national-scale list must keep payloads small (PRD §15) — a picker shows
 * names, not parent UUIDs the user can't read; (2) it denormalises the council + district <i>names</i>
 * (resolved server-side through the closure table) so the client renders a breadcrumb-style label in
 * one call. {@link WardDto} stays the canonical single-ward shape; this is the list/search projection.</p>
 *
 * <p>This carries no PII and no internal numeric id — only public reference data, safe for the
 * unauthenticated public reads it backs (ADR-0006, PRD §11/§22.6).</p>
 *
 * @param id           the ward's public id (UUID) — the value clients pass when pinning a location.
 * @param code         official ward code.
 * @param name         ward display name (e.g. "Mengwe").
 * @param councilName  name of the ward's parent Council/LGA (Halmashauri), or {@code null} if the ward
 *                     has no resolvable council ancestor (tolerates incomplete seed chains).
 * @param districtName name of the ward's District (Wilaya) ancestor, or {@code null} if unresolved.
 */
public record WardSummaryDto(
        UUID id,
        String code,
        String name,
        String councilName,
        String districtName
) {
}
