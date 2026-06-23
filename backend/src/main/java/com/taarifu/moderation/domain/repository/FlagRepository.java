package com.taarifu.moderation.domain.repository;

import com.taarifu.moderation.domain.model.Flag;
import com.taarifu.moderation.domain.model.enums.FlagSubjectType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Flag} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: persists citizen flags and supports the de-duplication that keeps a subject's flag
 * count honest — {@link #existsByFlaggerProfileIdAndSubjectTypeAndSubjectId} backs the
 * one-flag-per-citizen-per-subject rule before insert (the DB UNIQUE constraint is the hard backstop),
 * and {@link #findBySubjectTypeAndSubjectId} gathers a subject's flags to close them when its queue item
 * is resolved.</p>
 */
public interface FlagRepository extends JpaRepository<Flag, Long> {

    /**
     * @param publicId the flag's public id.
     * @return the flag, or empty.
     */
    Optional<Flag> findByPublicId(UUID publicId);

    /**
     * @param flaggerProfileId the flagging citizen's profile public id.
     * @param subjectType      the kind of content.
     * @param subjectId        the content's public id.
     * @return {@code true} if this citizen already has a live flag on this subject (anti-brigading).
     */
    boolean existsByFlaggerProfileIdAndSubjectTypeAndSubjectId(
            UUID flaggerProfileId, FlagSubjectType subjectType, UUID subjectId);

    /**
     * @param subjectType the kind of content.
     * @param subjectId   the content's public id.
     * @return all live flags on this subject (used to close them when the queue item is resolved).
     */
    List<Flag> findBySubjectTypeAndSubjectId(FlagSubjectType subjectType, UUID subjectId);
}
