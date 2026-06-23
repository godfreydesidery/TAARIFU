package com.taarifu.communications;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.communications.application.service.SubscriptionService;
import com.taarifu.communications.domain.model.Subscription;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import com.taarifu.communications.domain.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SubscriptionService} — idempotent follow/unfollow + ownership (M4, UC-G05).
 *
 * <p>Responsibility: pins that re-following an already-followed target returns the existing edge (no
 * duplicate), unfollowing a target you do not own is forbidden, and an unknown target type is a
 * validation failure. The ownership test fails if the guard is removed — a citizen must never unfollow on
 * another user's behalf.</p>
 */
class SubscriptionServiceTest {

    private SubscriptionRepository repository;
    private SubscriptionService service;
    private final UUID caller = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(SubscriptionRepository.class);
        service = new SubscriptionService(repository);
        when(repository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void follow_isIdempotent_returnsExistingEdge() {
        Subscription existing = Subscription.follow(caller, SubscriptionTargetType.AREA, target);
        when(repository.findByFollowerProfileIdAndTargetTypeAndTargetId(
                caller, SubscriptionTargetType.AREA, target)).thenReturn(Optional.of(existing));

        Subscription out = service.follow(caller, "AREA", target);

        assertThat(out).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void follow_createsEdge_whenNoneExists() {
        when(repository.findByFollowerProfileIdAndTargetTypeAndTargetId(any(), any(), any()))
                .thenReturn(Optional.empty());

        Subscription out = service.follow(caller, "CATEGORY", target);

        assertThat(out.getTargetType()).isEqualTo(SubscriptionTargetType.CATEGORY);
        assertThat(out.getFollowerProfileId()).isEqualTo(caller);
        verify(repository).save(any(Subscription.class));
    }

    @Test
    void unfollow_anotherUsersEdge_isForbidden() {
        Subscription othersEdge =
                Subscription.follow(UUID.randomUUID(), SubscriptionTargetType.AREA, target);
        when(repository.findByPublicId(any())).thenReturn(Optional.of(othersEdge));

        assertThatThrownBy(() -> service.unfollow(caller, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(repository, never()).save(any());
    }

    @Test
    void unfollow_ownEdge_softDeletes() {
        Subscription mine = Subscription.follow(caller, SubscriptionTargetType.AREA, target);
        when(repository.findByPublicId(any())).thenReturn(Optional.of(mine));

        service.unfollow(caller, UUID.randomUUID());

        assertThat(mine.isDeleted()).isTrue();
        verify(repository).save(mine);
    }

    @Test
    void follow_unknownTargetType_isValidationFailure() {
        assertThatThrownBy(() -> service.follow(caller, "PLANET", target))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
