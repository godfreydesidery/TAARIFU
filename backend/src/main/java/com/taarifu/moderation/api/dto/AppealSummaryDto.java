package com.taarifu.moderation.api.dto;

import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.domain.model.Appeal;
import com.taarifu.moderation.domain.model.enums.AppealStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary read model for an {@link Appeal} as it appears in the <b>moderator appeals queue</b>
 * (PRD §25.8, UC-H03; web-admin appeals triage).
 *
 * <p>Responsibility: the lean list row backing {@code GET /moderation/appeals}. Moderator-facing only
 * (gated by {@code ROLE_MODERATOR}); never published. It surfaces just enough to triage an appeal —
 * which content kind was actioned ({@link #subjectType}), who is appealing ({@link #appellant}), the
 * lifecycle {@link #status}, when it was filed ({@link #filedAt}), and the {@link #outcome} once
 * decided — without leaking the moderated content, the appellant's PII, or the appeal grounds. The
 * heavier single-appeal view ({@link AppealDto}) carries the appealed action reference.</p>
 *
 * <p>WHY {@code subjectType} is joined from the appealed action's queue item (not stored on the appeal):
 * an appeal is always anchored to one {@code ModerationAction}, which freezes the {@code ModerationItem}
 * it resolved; the queue projection joins {@code appeal → action → item} so the moderator sees the
 * content kind in one query with no N+1 (the repository selects this directly — see
 * {@code AppealRepository#findSummaries}).</p>
 *
 * <p>WHY {@link #appellant} is the appellant's <b>account</b> public id (not a profile id): it is the
 * frozen account-grain reference the appeal was filed under (the JWT-subject grain — see {@link Appeal}),
 * carried here purely as an opaque cross-module reference for the admin console to resolve a display name
 * via the identity module's own port. No PII crosses this DTO (ISOLATION: reference by UUID only).</p>
 *
 * @param publicId    the appeal's public id.
 * @param subjectType the kind of content the appealed action targeted (joined from the queue item).
 * @param appellant   the appellant's account public id (opaque cross-module reference; never PII).
 * @param status      the appeal lifecycle state.
 * @param filedAt     when the appeal was filed (UTC).
 */
public record AppealSummaryDto(
        UUID publicId,
        FlagSubjectType subjectType,
        UUID appellant,
        AppealStatus status,
        Instant filedAt
) {

    /**
     * Derives the appeal <b>outcome</b> for the queue row.
     *
     * <p>WHY derived rather than a separate stored column: an appeal's outcome <i>is</i> its terminal
     * status ({@link AppealStatus#UPHELD} / {@link AppealStatus#OVERTURNED}); while still
     * {@link AppealStatus#OPEN} there is no outcome yet, so this returns {@code null}. Keeping it derived
     * means the projection selects {@code status} once and the two values can never drift.</p>
     *
     * @return the terminal outcome ({@code UPHELD}/{@code OVERTURNED}), or {@code null} while OPEN.
     */
    public AppealStatus outcome() {
        return status == AppealStatus.OPEN ? null : status;
    }
}
