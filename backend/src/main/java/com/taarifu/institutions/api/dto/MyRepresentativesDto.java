package com.taarifu.institutions.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * The "find my representatives" result bundle (PRD §22.6 first-class flow; UC-C01).
 *
 * <p>Responsibility: for a resolved ward, returns the citizen's set of representatives — the
 * <b>MP</b> (resolved via the Ward→Constituency effective-dated mapping), the <b>Councillor (Diwani)</b>
 * and any <b>ward/village executive officer</b> (resolved directly from the ward). Each is a lean
 * {@link RepresentativeSummaryDto}.</p>
 *
 * <p>WHY a single bundle and not three calls: a feature-phone citizen on 2G must get "who represents me"
 * in one request (PRD §15, §22.6). WHY the {@code mp} may be {@code null}: a ward may have no current
 * Ward→Constituency mapping, or the constituency seat may be vacant/being onboarded — the client shows a
 * "rep being onboarded" state rather than an error (PRD R2 mitigation), so the flow never hard-fails.</p>
 *
 * @param wardId           the resolved ward's public id.
 * @param wardName         the resolved ward's name.
 * @param constituencyId   the ward's current constituency public id, or {@code null} if unmapped.
 * @param constituencyName the current constituency name, or {@code null}.
 * @param mp               the sitting MP for the constituency, or {@code null} if none/vacant.
 * @param councillors      sitting councillors (Madiwani) for the ward (usually one; list for safety).
 * @param wardExecutives   sitting ward/village executive officers for the ward (may be empty).
 */
public record MyRepresentativesDto(
        UUID wardId,
        String wardName,
        UUID constituencyId,
        String constituencyName,
        RepresentativeSummaryDto mp,
        List<RepresentativeSummaryDto> councillors,
        List<RepresentativeSummaryDto> wardExecutives
) {
}
