package com.taarifu.identity.application.service;

import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.SubjectContentQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Identity's implementation of the moderation {@link SubjectContentQueryApi} for
 * {@link FlagSubjectType#PROFILE} subjects (US-12.3, UC-H05, EI-18, D-Q8; ADR-0018; ADR-0013 §4c). It lets the
 * moderation auto-assist screen resolve a flagged user/organisation profile to its <b>public, scorable text</b>
 * so the {@code ContentSafety} scorer can screen it (e.g. a profanity/slur/impersonation display name), without
 * moderation importing identity's internals.
 *
 * <p>Responsibility: given a flagged profile public id, return the profile's <b>public display text</b> — the
 * display name (a person's first+last, or an organisation's name), via the single
 * {@link Profile#displayName()} composition. That is the only free text a profile carries that is publicly
 * visible and a moderator could act on. It is the read-side twin of identity's other published read ports;
 * one bean per moderation concern, registered into moderation's {@code SubjectContentResolver} registry by
 * Spring, so moderation never imports identity's {@code domain} (ARCHITECTURE §3.2; dependency-inversion —
 * moderation owns the interface, identity provides the impl).</p>
 *
 * <h3>🔒 PII discipline — the load-bearing invariant (PRD §18, PDPA)</h3>
 * <p>This port returns <b>only the public display name</b>. It MUST NEVER surface — and this implementation
 * does not read — any of the following:</p>
 * <ul>
 *   <li>the national/voter {@code idNo} (field-encrypted) or the {@code idHash} blind index — the
 *       highest-sensitivity data on the platform; a single byte leaking into a scorer call / log would be an
 *       R10/R11/R12-grade breach;</li>
 *   <li>the phone (E.164) or email — contact PII (those have their own consent-fenced
 *       {@code RecipientContactApi} dispatch path, never this screen);</li>
 *   <li>date of birth, gender, nationality, or any other demographic.</li>
 * </ul>
 * <p>The returned text is handled <b>transiently</b> by moderation: scored inside the flag/triage transaction
 * and then discarded — never persisted on the queue item, in an event, or in a log (see
 * {@link SubjectContentQueryApi}). A profile mid-completion (no name yet) or one already crypto-shredded by
 * erasure ({@link Profile#anonymise(String)} leaves only the {@code anonymized_user_<short>} tombstone)
 * resolves to that label or to empty — never resurrected PII.</p>
 *
 * <p><b>🔒 Assist only (D-Q8, R21).</b> This port produces input to a <i>screen</i>, never an action: the text
 * it returns can only cause a queue item to be held-for-review and prioritised — it can never approve, hide,
 * remove, suspend, or sanction. The human pipeline is always the floor; a non-existent / soft-deleted
 * (anonymised) profile resolves to {@link Optional#empty()} so the screen is skipped and the flagged item
 * still reaches a human moderator (EI-18 floor).</p>
 *
 * <p>WHY {@code @Transactional(readOnly = true)}: a pure lookup of one aggregate, no mutation — the same shape
 * as {@link RecipientContactService} so identity publishes one small, audit-greppable bean per moderation
 * concern with no new pattern (ADR-0013 §1 "one port per concern, kept small").</p>
 */
@Service
@Transactional(readOnly = true)
public class ProfileSubjectContentQuery implements SubjectContentQueryApi {

    private final ProfileRepository profileRepository;

    /**
     * @param profileRepository profile persistence port (content lookup by public id).
     */
    public ProfileSubjectContentQuery(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    /** {@inheritDoc} */
    @Override
    public FlagSubjectType subjectType() {
        return FlagSubjectType.PROFILE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the profile's public display name (the only publicly-visible, moderator-actionable free text a
     * profile carries) — never any ID/contact/demographic PII (see the type Javadoc). A non-existent or
     * soft-deleted/anonymised profile (the {@code @SQLRestriction} hides tombstones), or a profile with no name
     * set yet, resolves to {@link Optional#empty()} — the auto-assist screen is then skipped and the flagged
     * item still goes to a human moderator (the human-pipeline floor, EI-18).</p>
     */
    @Override
    public Optional<String> contentTextOf(UUID subjectId) {
        if (subjectId == null) {
            return Optional.empty();
        }
        // Public display name ONLY. Optional#map drops a null display name to empty (skip the screen), so a
        // name-less / tombstoned profile never produces a scorer call. No idNo/idHash/phone/email is read here.
        return profileRepository.findByPublicId(subjectId)
                .map(Profile::displayName);
    }
}
