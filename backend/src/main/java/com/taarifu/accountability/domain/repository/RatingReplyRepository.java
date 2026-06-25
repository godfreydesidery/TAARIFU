package com.taarifu.accountability.domain.repository;

import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.model.RatingReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link RatingReply} — the representative right-of-reply (ARCHITECTURE.md
 * &sect;3.3; PRD &sect;10 Epic M6, US-6.2).
 *
 * <p>Responsibility: the one-per-rating reply lookup (backs the duplicate guard and the revise path) and the
 * batch read used to attach replies to a page of ratings. There is no token-balance read anywhere here — a
 * right-of-reply is a fairness counterweight to a fenced binding action, never a token-gated one (&sect;23).</p>
 */
public interface RatingReplyRepository extends JpaRepository<RatingReply, Long> {

    /**
     * The one-per-rating lookup: does this rating already have a reply? Backs both the pre-insert duplicate
     * guard and the author's revise path (edit the existing reply rather than create a second).
     *
     * @param rating the answered rating (same-module aggregate).
     * @return the existing reply, or empty.
     */
    Optional<RatingReply> findByRating(Rating rating);

    /**
     * @param publicId the reply's public id.
     * @return the reply, or empty.
     */
    Optional<RatingReply> findByPublicId(UUID publicId);

    /**
     * Batch-loads the replies for a set of rating database ids — used to attach each reply to its rating in a
     * single query (avoids the N+1 when listing a representative's rated comments with their replies).
     *
     * @param ratingIds the database ids of the ratings whose replies to load.
     * @return the replies for those ratings (may be fewer than the input — not every rating has a reply).
     */
    List<RatingReply> findByRating_IdIn(List<Long> ratingIds);
}
