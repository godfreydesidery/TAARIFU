package com.taarifu.geography.api.dto;

import java.util.List;

/**
 * The result of resolving a pinned place (GPS or a chosen ward) to <b>both</b> Tanzanian geographies
 * (PRD §9.0, §11 M1 "find my representative").
 *
 * <p>Responsibility: the single response carrying everything derived from a pin: the resolved ward,
 * its full administrative ancestor chain (Region→…→Council/Division), and the electoral constituency
 * the ward maps to (effective today). This is the contract behind {@code GET /locations/resolve} — the
 * citizen never types a constituency; the system derives it (PRD §9.0).</p>
 *
 * <p>WHY both chains in one payload: PRD §9.0 mandates that pinning a place derives the administrative
 * chain <i>and</i> the electoral mapping together; returning them in one lean response saves a
 * low-bandwidth client multiple round-trips (PRD §15).</p>
 *
 * <p>{@code constituency} is {@code null} when the ward has no effective mapping (degradation: the
 * admin chain still resolves, EI-7). {@code resolved} is {@code false} when GPS hit no ward boundary
 * and the client should fall back to manual ward drill-down.</p>
 *
 * @param resolved      whether a ward was resolved (false → client uses manual drill-down).
 * @param ward          the resolved ward, or {@code null} if unresolved.
 * @param adminChain    the ward's ancestors top-down (Region first); empty if unresolved.
 * @param constituency  the ward's current constituency, or {@code null} if none/unmapped.
 */
public record LocationResolutionDto(
        boolean resolved,
        WardDto ward,
        List<LocationDto> adminChain,
        ConstituencyDto constituency
) {
}
