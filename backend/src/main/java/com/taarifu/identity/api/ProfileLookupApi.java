package com.taarifu.identity.api;

import com.taarifu.identity.api.dto.ProfileSummary;

import java.util.Optional;
import java.util.UUID;

/**
 * The identity module's <b>public, in-process query port</b> for resolving a profile's public id and public
 * display name — the small "who authored this?" read seam sibling feature modules need when they hold only an
 * <b>account</b> public id (the JWT-subject grain) or a <b>profile</b> public id and must show a human-readable
 * author label (ADR-0013 §1, §4; the {@code TokenLedgerApi}/{@code RecipientContactApi}-shaped house pattern).
 *
 * <p>Responsibility: answer two narrow, read-only questions, resolved from the identity {@code Profile} ↔
 * {@code app_user} (1:1) aggregate that identity owns — and nothing else:</p>
 * <ol>
 *   <li>"given a citizen's <b>account</b> public id, what is their <b>profile</b> public id?" — the exact need
 *       behind the engagement {@code // TODO(wiring): resolve creatorPublicId (account) -> identity Profile
 *       public id} markers on petition/poll/question authoring (PRD §12.2). Those create paths carry the
 *       authenticated <b>account</b> id (from {@code CurrentUser}); to record/display the author by profile they
 *       must map it through identity, which owns the 1:1 link — they must NOT reach into identity's tables;</li>
 *   <li>"given a <b>profile</b> public id, what is its public <b>display name</b>?" — the profile-public-id ↔
 *       display lookup a feed/list/detail view uses to label an author.</li>
 * </ol>
 *
 * <p>The {@code communications}/{@code engagement}/{@code accountability} modules call this synchronously
 * (feature → identity, a sanctioned acyclic edge — identity never calls them back, ARCHITECTURE §3.2),
 * <b>without</b> importing identity's {@code domain}/{@code repository}. The caller treats every result as
 * opaque truth and never reaches past it. The implementation lives in {@code identity.application.service} as a
 * {@code @Transactional(readOnly = true)} {@code @Service}; callers inject this interface, never the impl
 * (ADR-0013 §1).</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA — data minimisation):</b> this port exposes <b>only</b> public ids and
 * the public display name (via {@link com.taarifu.identity.domain.model.Profile#displayName()}). It returns
 * <b>no</b> national/voter {@code idNo} or blind index, <b>no</b> phone/email (contact PII has its own
 * consent-fenced {@code RecipientContactApi} dispatch path), and <b>no</b> demographic — see
 * {@link ProfileSummary}. WHY a dedicated narrow port (not a field added to a broader profile DTO): least
 * privilege — author-labelling callers get exactly the id+name they need behind one small, audit-greppable
 * interface (ISP, ADR-0013 "one port per concern, kept small"), so widening this view can never silently leak
 * contact or ID PII to every caller. Deny-by-default: an unknown id (or {@code null}) yields
 * {@link Optional#empty()}, never an exception — a caller renders "unknown author" gracefully rather than
 * crashing the read path.</p>
 */
public interface ProfileLookupApi {

    /**
     * Resolves a citizen's <b>account</b> public id to their <b>profile</b> public id (the 1:1 account↔profile
     * link identity owns). This is the mapping the engagement authoring paths need to record/display a creator
     * by profile when they hold only the authenticated account id.
     *
     * @param accountPublicId the account's public id (the JWT-subject grain, e.g. from {@code CurrentUser});
     *                        {@code null} resolves to empty.
     * @return the owning profile's public id, or {@link Optional#empty()} if no account/profile resolves for
     *         the id (deny-by-default — the caller treats it as "no profile", never an error). The
     *         {@code @SQLRestriction} on profile/account excludes soft-deleted (anonymised) rows.
     */
    Optional<UUID> profileIdForAccount(UUID accountPublicId);

    /**
     * Resolves a <b>profile</b> public id to its public summary (profile id + display name) for author
     * labelling — the profile-public-id ↔ display lookup.
     *
     * @param profilePublicId the profile's public id; {@code null} resolves to empty.
     * @return the profile's privacy-minimised summary (id + public display name only — no PII), or
     *         {@link Optional#empty()} if no profile resolves for the id (deny-by-default).
     */
    Optional<ProfileSummary> profileSummary(UUID profilePublicId);
}
