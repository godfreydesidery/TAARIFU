package com.taarifu.moderation.api;

import java.util.Optional;
import java.util.UUID;

/**
 * A per-owner <b>subject-author lookup port</b> that the moderation module dispatches to, to resolve a
 * flagged {@code (subjectType, subjectId)} to the author's account public id (ADR-0013 §4c; D16).
 *
 * <p>Responsibility: invert the moderation boundary safely. Moderation holds only the opaque
 * {@code (subjectType, subjectId)} and must <b>not</b> import any content-owning module
 * (reporting/engagement/communications/…) — so each owner module publishes an implementation of THIS
 * interface (declaring the one {@link FlagSubjectType} it serves via {@link #subjectType()}), and
 * moderation injects them as a registry keyed by subject type. The owner answers "who authored this
 * record?" and moderation feeds that author to the {@code isNotSelf} self-action guard (D16) so a
 * moderator can never action their own content.</p>
 *
 * <p><b>GRAIN CONTRACT (load-bearing for D16):</b> the returned id MUST be the author's <b>account public
 * id</b> ({@code app_user.publicId}) — the same grain as {@code CurrentUser.publicId()} that the
 * self-action guard compares against. Owners that reference their author by the account public id (as
 * reporting/engagement do — the reporter/creator id is taken from {@code CurrentUser.requirePublicId()})
 * satisfy this directly. Returning a display/profile-only id would make the conflict check silently never
 * match (a security defect), so it must not be done.</p>
 *
 * <p>WHY this interface lives in {@code moderation.api} (not in each owner's api): moderation owns the
 * {@link FlagSubjectType} taxonomy and the dispatch, and is a <i>foundation</i> module — so a feature owner
 * (reporting/engagement) implementing it is a sanctioned feature→foundation {@code api} dependency
 * (ARCHITECTURE §3.2), with no module importing another's internals.</p>
 */
public interface SubjectAuthorQueryApi {

    /**
     * @return the single {@link FlagSubjectType} this owner resolves (its registry key). Each owner serves
     *         exactly one subject type so moderation's dispatch is unambiguous.
     */
    FlagSubjectType subjectType();

    /**
     * Resolves the author of a moderatable subject to their <b>account public id</b> (see the grain
     * contract on the type Javadoc).
     *
     * @param subjectId the flagged content's public id (in the owner's module).
     * @return the author's account public id, or {@link Optional#empty()} if the subject does not exist or
     *         has no surfaced author (e.g. an anonymous sensitive report — D-Q1); empty leaves the D16
     *         self-action guard vacuously satisfied (there is no author to conflict with).
     */
    Optional<UUID> authorOf(UUID subjectId);
}
