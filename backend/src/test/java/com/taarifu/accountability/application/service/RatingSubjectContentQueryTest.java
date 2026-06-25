package com.taarifu.accountability.application.service;

import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.accountability.domain.repository.RatingRepository;
import com.taarifu.moderation.api.FlagSubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RatingSubjectContentQuery} — accountability's content-lookup port that lets
 * moderation's auto-assist scorer read a flagged rating's comment (ADR-0018; ADR-0013 §4c) without a
 * boundary breach. Mockito only (no Docker), runs in every local build.
 *
 * <p>Responsibility: pins the contract moderation depends on — the bean serves {@code RATING}, returns the
 * rating's free-text comment for the screen, and returns {@link Optional#empty()} when there is no scorable
 * text (missing rating, or a score-only rating with a blank comment) so the auto-assist screen is skipped
 * and the flagged item still goes to a human (the EI-18 human-pipeline floor). Each test fails if that
 * behaviour regresses.</p>
 */
class RatingSubjectContentQueryTest {

    private RatingRepository ratingRepository;
    private RatingSubjectContentQuery query;

    @BeforeEach
    void setUp() {
        ratingRepository = mock(RatingRepository.class);
        query = new RatingSubjectContentQuery(ratingRepository);
    }

    @Test
    void serves_theRatingSubjectType() {
        // The registry key moderation dispatches on must be RATING — accountability owns the only flaggable
        // free text (a rating comment, US-6.2). A wrong key would silently never screen rating comments.
        assertThat(query.subjectType()).isEqualTo(FlagSubjectType.RATING);
    }

    @Test
    void returnsTheComment_whenTheRatingHasOne() {
        UUID ratingId = UUID.randomUUID();
        Rating rating = Rating.create(RatingSubjectType.REPRESENTATIVE, UUID.randomUUID(),
                UUID.randomUUID(), 2, "Hujafanya kazi, mjinga!", "2026-Q2");
        when(ratingRepository.findByPublicId(ratingId)).thenReturn(Optional.of(rating));

        // The scorable text is the comment exactly (it is surfaced transiently to the scorer; moderation
        // never persists it). Fails if the wrong field (score/period) or nothing is returned.
        assertThat(query.contentTextOf(ratingId)).contains("Hujafanya kazi, mjinga!");
    }

    @Test
    void returnsEmpty_forAScoreOnlyRating_soTheScreenIsSkipped() {
        UUID ratingId = UUID.randomUUID();
        Rating rating = Rating.create(RatingSubjectType.REPRESENTATIVE, UUID.randomUUID(),
                UUID.randomUUID(), 5, null, "2026-Q2");
        when(ratingRepository.findByPublicId(ratingId)).thenReturn(Optional.of(rating));

        // No comment → no scorable text → empty → the auto-assist screen is skipped and the flagged item
        // still goes to a human moderator (EI-18 floor). Fails if a null comment leaks as a non-empty body.
        assertThat(query.contentTextOf(ratingId)).isEmpty();
    }

    @Test
    void returnsEmpty_forABlankComment() {
        UUID ratingId = UUID.randomUUID();
        Rating rating = Rating.create(RatingSubjectType.REPRESENTATIVE, UUID.randomUUID(),
                UUID.randomUUID(), 3, "   ", "2026-Q2");
        when(ratingRepository.findByPublicId(ratingId)).thenReturn(Optional.of(rating));

        assertThat(query.contentTextOf(ratingId)).isEmpty();
    }

    @Test
    void returnsEmpty_whenTheRatingDoesNotExist() {
        UUID missing = UUID.randomUUID();
        when(ratingRepository.findByPublicId(missing)).thenReturn(Optional.empty());

        assertThat(query.contentTextOf(missing)).isEmpty();
    }
}
