package com.taarifu.engagement.application.service;

import com.taarifu.engagement.domain.repository.PetitionRepository;
import com.taarifu.moderation.api.SubjectAuthorQueryApi;
import com.taarifu.moderation.api.FlagSubjectType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Engagement's implementation of the moderation {@link SubjectAuthorQueryApi} for
 * {@link FlagSubjectType#PETITION} subjects (ADR-0013 §4c; D16). It lets moderation resolve a flagged
 * petition to its author for the self-action guard, without moderation importing engagement's internals.
 *
 * <p>Responsibility: given a petition public id, return the creating person's account public id, or empty
 * for an organisation-authored petition (no single natural-person account author) or a non-existent
 * petition. The {@code creatorProfileId} is taken from {@code CurrentUser.requirePublicId()} at create
 * time, so it is already the <b>account public id</b> the moderation grain contract requires.</p>
 */
@Service
@Transactional(readOnly = true)
public class PetitionSubjectAuthorQuery implements SubjectAuthorQueryApi {

    private final PetitionRepository petitionRepository;

    /**
     * @param petitionRepository petition persistence port (author lookup by public id).
     */
    public PetitionSubjectAuthorQuery(PetitionRepository petitionRepository) {
        this.petitionRepository = petitionRepository;
    }

    /** {@inheritDoc} */
    @Override
    public FlagSubjectType subjectType() {
        return FlagSubjectType.PETITION;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> authorOf(UUID subjectId) {
        // creatorProfileId is the authoring person's account public id, or null for an org-authored petition
        // (no natural-person account to conflict with) — null is the "no surfaced author" case.
        return petitionRepository.findByPublicId(subjectId)
                .map(p -> p.getCreatorProfileId());
    }
}
