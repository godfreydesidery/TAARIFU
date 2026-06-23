package com.taarifu.geography.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for a Constituency (Jimbo), optionally including its current member wards
 * (PRD §9.0, §11 M1).
 *
 * <p>Responsibility: the boundary shape for {@code GET /constituencies/{publicId}}. The {@code wards}
 * list is resolved <b>as of today</b> through the effective-dated {@link
 * com.taarifu.geography.domain.model.WardConstituency} bridge — so it reflects the <i>current</i>
 * delimitation, not a stale snapshot (PRD §9.0).</p>
 *
 * @param id         the constituency's public id (UUID).
 * @param code       official electoral code.
 * @param name       constituency display name (e.g. "Rombo").
 * @param districtId the homing district's public id, or {@code null} if not linked.
 * @param wards      the constituency's current member wards (may be empty).
 */
public record ConstituencyDto(
        UUID id,
        String code,
        String name,
        UUID districtId,
        List<WardDto> wards
) {
}
