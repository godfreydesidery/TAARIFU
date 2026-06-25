package com.taarifu.engagement.domain.repository;

import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.enums.PetitionStatus;
import com.taarifu.engagement.domain.model.enums.PetitionTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Petition} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: petition lookups by {@code publicId} and public listing by status. Soft-deleted
 * rows are excluded by the entity's {@code @SQLRestriction}. The status-scoped list lets the public
 * endpoint show only non-DRAFT petitions (PRD §22.6 — drafts and moderation-held items excluded).</p>
 */
public interface PetitionRepository extends JpaRepository<Petition, Long> {

    /**
     * @param publicId the petition's public id.
     * @return the matching petition, or empty if none/soft-deleted.
     */
    Optional<Petition> findByPublicId(UUID publicId);

    /**
     * Lists petitions whose status is in the given set (used to expose only publicly-visible states).
     *
     * @param statuses the allowed statuses (e.g. all but DRAFT for the public list).
     * @param pageable bounded paging/sorting.
     * @return a page of petitions.
     */
    Page<Petition> findByStatusIn(Collection<PetitionStatus> statuses, Pageable pageable);

    /**
     * Lists publicly-visible petitions filtered to a target type (REPRESENTATIVE / OFFICE) — the citizen
     * read-depth filter so a constituent can browse, e.g., only petitions addressed to representatives.
     * Hits {@code ix_petition_target} + {@code ix_petition_status}. Soft-deleted/DRAFT rows are excluded by
     * the {@code @SQLRestriction} and the {@code statuses} set respectively.
     *
     * @param targetType the addressee discriminator to filter by.
     * @param statuses   the allowed (public) statuses.
     * @param pageable   bounded paging/sorting.
     * @return a page of petitions of that target type.
     */
    Page<Petition> findByTargetTypeAndStatusIn(PetitionTargetType targetType,
                                               Collection<PetitionStatus> statuses, Pageable pageable);

    /**
     * Lists every petition the subject authored as a natural person, for the PDPA fan-out (data-subject
     * ACCESS export + ERASURE severing; ADR-0016 §4/§5). Org-authored petitions ({@code creatorProfileId}
     * null) never match, so an org's petitions are correctly untouched by a person's DSR.
     *
     * @param creatorProfileId the authoring person's account public id (the DSR subject).
     * @return every (non-deleted) petition still authored by this person; empty if none / already severed.
     */
    List<Petition> findByCreatorProfileId(UUID creatorProfileId);
}
