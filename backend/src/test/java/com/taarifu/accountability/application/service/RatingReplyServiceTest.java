package com.taarifu.accountability.application.service;

import com.taarifu.accountability.api.dto.CreateRatingReplyDto;
import com.taarifu.accountability.application.mapper.AccountabilityMapper;
import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.model.RatingReply;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.accountability.domain.port.RepresentativeOwnershipPort;
import com.taarifu.accountability.domain.repository.RatingReplyRepository;
import com.taarifu.accountability.domain.repository.RatingRepository;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RatingReplyService} — the right-of-reply ownership/conflict-of-interest fence in code
 * (PRD §10 US-6.2; the D-rated-fairness rule; D16).
 *
 * <p>Responsibility: proves the load-bearing fence rules <b>without</b> Docker — the self-reply path is
 * deny-by-default (the stub blocks every account → conflict-of-interest), the curator path bypasses the
 * ownership port (admin authority), a non-REPRESENTATIVE rating cannot be replied to, the author is taken
 * from the security context (never the body), the one-per-rating cap revises the existing reply, and a
 * concurrent second reply surfaces as a clean 409. Each test would fail if its guard were removed
 * (CLAUDE.md §10 — test the invariant, not the happy path).</p>
 */
@ExtendWith(MockitoExtension.class)
class RatingReplyServiceTest {

    @Mock
    private RatingRepository ratingRepository;
    @Mock
    private RatingReplyRepository ratingReplyRepository;
    @Mock
    private RepresentativeOwnershipPort ownershipPort;
    @Mock
    private AuditEventService audit;

    private final AccountabilityMapper mapper = new AccountabilityMapper();

    private final UUID caller = UUID.randomUUID();
    private final UUID representativeId = UUID.randomUUID();
    private final UUID rater = UUID.randomUUID();
    private final UUID ratingPublicId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticates the calling principal in the security context. */
    private void authenticate() {
        CurrentUser principal = new CurrentUser(caller, List.of("CITIZEN"), "T3");
        var auth = new UsernamePasswordAuthenticationToken(caller, null, List.of());
        auth.setDetails(principal);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private RatingReplyService service() {
        return new RatingReplyService(ratingRepository, ratingReplyRepository, ownershipPort, mapper, audit);
    }

    private Rating representativeRating() {
        return Rating.create(RatingSubjectType.REPRESENTATIVE, representativeId, rater, 3, "ok", "2026-Q2");
    }

    private CreateRatingReplyDto request() {
        return new CreateRatingReplyDto("Asante kwa maoni; tunaendelea na kazi.");
    }

    // ---------------------------------------------------------------------------------------------
    // Self-reply: the OWNERSHIP fence. Deny-by-default blocks; a real owner is allowed and keyed to caller.
    // ---------------------------------------------------------------------------------------------

    @Test
    void selfReply_denyByDefault_blocksAsConflictOfInterest_nothingPersisted() {
        authenticate();
        when(ratingRepository.findByPublicId(ratingPublicId)).thenReturn(Optional.of(representativeRating()));
        // The default deny-stub answers false for every account → the self-reply path is closed (fail-safe).
        when(ownershipPort.isLinkedAccountOf(caller, representativeId)).thenReturn(false);

        assertThatThrownBy(() -> service().replyAsRepresentative(ratingPublicId, request()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        // No reply may be persisted when the caller is not the rating's subject representative.
        verify(ratingReplyRepository, never()).saveAndFlush(any());
        verify(ratingReplyRepository, never()).save(any());
    }

    @Test
    void selfReply_byOwner_persists_authorIsCaller_notOnBehalf() {
        authenticate();
        when(ratingRepository.findByPublicId(ratingPublicId)).thenReturn(Optional.of(representativeRating()));
        when(ownershipPort.isLinkedAccountOf(caller, representativeId)).thenReturn(true);
        when(ratingReplyRepository.findByRating(any())).thenReturn(Optional.empty());
        when(ratingReplyRepository.saveAndFlush(any(RatingReply.class))).thenAnswer(inv -> inv.getArgument(0));

        service().replyAsRepresentative(ratingPublicId, request());

        // The author is the CALLER's identity from the security context — never a body field; onBehalf=false.
        ArgumentCaptor<RatingReply> captor = ArgumentCaptor.forClass(RatingReply.class);
        verify(ratingReplyRepository).saveAndFlush(captor.capture());
        RatingReply saved = captor.getValue();
        assertThat(saved.getAuthorAccountId()).isEqualTo(caller);
        assertThat(saved.getRepresentativeId()).isEqualTo(representativeId);
        assertThat(saved.isOnBehalf()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Curated on-behalf: the ADMIN authority bypasses the ownership port; the reply is flagged onBehalf.
    // ---------------------------------------------------------------------------------------------

    @Test
    void curatorReply_bypassesOwnershipPort_persistsOnBehalf() {
        authenticate();
        when(ratingRepository.findByPublicId(ratingPublicId)).thenReturn(Optional.of(representativeRating()));
        when(ratingReplyRepository.findByRating(any())).thenReturn(Optional.empty());
        when(ratingReplyRepository.saveAndFlush(any(RatingReply.class))).thenAnswer(inv -> inv.getArgument(0));

        service().replyAsCurator(ratingPublicId, request());

        // The curated path NEVER consults the ownership port (admin authority is the endpoint's @PreAuthorize).
        verify(ownershipPort, never()).isLinkedAccountOf(any(), any());
        ArgumentCaptor<RatingReply> captor = ArgumentCaptor.forClass(RatingReply.class);
        verify(ratingReplyRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().isOnBehalf()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // Subject-must-be-REPRESENTATIVE: an OFFICE/PROJECT rating has no representative to reply.
    // ---------------------------------------------------------------------------------------------

    @Test
    void reply_toNonRepresentativeRating_blockedAsConflictOfInterest() {
        authenticate();
        Rating officeRating = Rating.create(RatingSubjectType.OFFICE, representativeId, rater, 4, null, "2026-Q2");
        when(ratingRepository.findByPublicId(ratingPublicId)).thenReturn(Optional.of(officeRating));

        assertThatThrownBy(() -> service().replyAsRepresentative(ratingPublicId, request()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        // The subject-type guard runs BEFORE the ownership check; nothing is persisted.
        verify(ownershipPort, never()).isLinkedAccountOf(any(), any());
        verify(ratingReplyRepository, never()).saveAndFlush(any());
    }

    @Test
    void reply_toMissingRating_notFound() {
        authenticate();
        when(ratingRepository.findByPublicId(ratingPublicId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().replyAsRepresentative(ratingPublicId, request()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // One reply per rating: an existing reply is REVISED, never duplicated; a concurrent insert is a 409.
    // ---------------------------------------------------------------------------------------------

    @Test
    void selfReply_existingReply_isRevised_notDuplicated() {
        authenticate();
        Rating rating = representativeRating();
        when(ratingRepository.findByPublicId(ratingPublicId)).thenReturn(Optional.of(rating));
        when(ownershipPort.isLinkedAccountOf(caller, representativeId)).thenReturn(true);
        RatingReply existing = RatingReply.create(rating, representativeId, caller, false, "old reply");
        when(ratingReplyRepository.findByRating(rating)).thenReturn(Optional.of(existing));
        when(ratingReplyRepository.save(any(RatingReply.class))).thenAnswer(inv -> inv.getArgument(0));

        service().replyAsRepresentative(ratingPublicId, request());

        // One-per-rating: the EXISTING reply is edited in place, never a second insert.
        verify(ratingReplyRepository).save(existing);
        verify(ratingReplyRepository, never()).saveAndFlush(any());
        assertThat(existing.getBody()).isEqualTo("Asante kwa maoni; tunaendelea na kazi.");
    }

    @Test
    void concurrentSecondReply_surfacesAsConflict() {
        authenticate();
        when(ratingRepository.findByPublicId(ratingPublicId)).thenReturn(Optional.of(representativeRating()));
        when(ownershipPort.isLinkedAccountOf(caller, representativeId)).thenReturn(true);
        when(ratingReplyRepository.findByRating(any())).thenReturn(Optional.empty());
        // The DB unique (one reply per rating) fires on a concurrent double-post → clean 409, not a 500.
        when(ratingReplyRepository.saveAndFlush(any(RatingReply.class)))
                .thenThrow(new DataIntegrityViolationException("ux_rating_reply_one_per_rating"));

        assertThatThrownBy(() -> service().replyAsRepresentative(ratingPublicId, request()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------------------------------------
    // Audit: a successful reply appends RATING_REPLY_POSTED with refs/codes only (no PII).
    // ---------------------------------------------------------------------------------------------

    @Test
    void onSuccess_emitsRatingReplyPostedAudit_refsOnly_noPii() {
        authenticate();
        when(ratingRepository.findByPublicId(ratingPublicId)).thenReturn(Optional.of(representativeRating()));
        when(ownershipPort.isLinkedAccountOf(caller, representativeId)).thenReturn(true);
        when(ratingReplyRepository.findByRating(any())).thenReturn(Optional.empty());
        when(ratingReplyRepository.saveAndFlush(any(RatingReply.class))).thenAnswer(inv -> inv.getArgument(0));

        service().replyAsRepresentative(ratingPublicId, request());

        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(ev.capture());
        AuditEvent e = ev.getValue();
        assertThat(e.getEventType()).isEqualTo(AuditEventType.RATING_REPLY_POSTED);
        assertThat(e.getActorPublicId()).isEqualTo(caller);
        assertThat(e.getSubjectPublicId()).isEqualTo(representativeId);
        assertThat(e.getReasonCode()).isEqualTo("SELF");
        // The non-PII reason carries no reply body.
        assertThat(e.getReasonCode()).doesNotContain("Asante");
    }
}
