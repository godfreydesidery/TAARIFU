package com.taarifu.accountability.application.service;

import com.taarifu.accountability.api.dto.CreateRatingDto;
import com.taarifu.accountability.application.mapper.AccountabilityMapper;
import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.model.enums.RatingSubjectType;
import com.taarifu.accountability.domain.repository.RatingRepository;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.identity.api.ElectoralScopeApi;
import com.taarifu.institutions.api.RepresentativeQueryApi;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RatingService} — the civic-integrity fence in code (PRD §10 US-6.2; §23; D16/D18).
 *
 * <p>Responsibility: proves the load-bearing fence rules <b>without</b> Docker — no self-action, the
 * rater key is the caller's own identity (never the body), one-per-person revises the rater's own row,
 * a DB unique violation surfaces as a clean {@code 409}, and <b>no token balance is ever consulted</b>
 * (the service has no token collaborator to consult — asserted by construction). Each test would fail if
 * its guard were removed (CLAUDE.md §10 — test the invariant, not the happy path).</p>
 */
@ExtendWith(MockitoExtension.class)
class RatingServiceTest {

    @Mock
    private RatingRepository ratingRepository;
    @Mock
    private ScopeGuard scopeGuard;
    @Mock
    private RepresentativeQueryApi representativeQueryApi;
    @Mock
    private ElectoralScopeApi electoralScopeApi;
    @Mock
    private AuditEventService audit;

    private final AccountabilityMapper mapper = new AccountabilityMapper();

    private final UUID caller = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticates a T3 caller in the security context (the controller's @RequiresTier already passed). */
    private void authenticateCaller() {
        CurrentUser principal = new CurrentUser(caller, List.of("CITIZEN"), "T3");
        var auth = new UsernamePasswordAuthenticationToken(caller, null, List.of());
        auth.setDetails(principal);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private CreateRatingDto request() {
        return new CreateRatingDto(RatingSubjectType.REPRESENTATIVE, subject, 4, "good work", "2026-Q2");
    }

    private RatingService service() {
        return new RatingService(ratingRepository, scopeGuard, representativeQueryApi, electoralScopeApi,
                mapper, audit);
    }

    @Test
    void blocksSelfRating_conflictOfInterest() {
        authenticateCaller();
        // The subject IS the caller → no-self-action must block (D16). Other collaborators irrelevant.
        when(scopeGuard.isNotSelf(subject)).thenReturn(false);

        assertThatThrownBy(() -> service().submit(request()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        // No row may be persisted on a blocked self-rating.
        verify(ratingRepository, never()).saveAndFlush(any());
        verify(ratingRepository, never()).save(any());
    }

    @Test
    void persistsWithCallerAsRater_notBodySupplied() {
        authenticateCaller();
        when(scopeGuard.isNotSelf(subject)).thenReturn(true);
        when(ratingRepository.findBySubjectTypeAndSubjectIdAndRaterProfileIdAndPeriod(
                RatingSubjectType.REPRESENTATIVE, subject, caller, "2026-Q2"))
                .thenReturn(Optional.empty());
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        service().submit(request());

        // The rater key is the CALLER's identity from the security context — never a body field.
        ArgumentCaptor<Rating> captor = ArgumentCaptor.forClass(Rating.class);
        verify(ratingRepository).saveAndFlush(captor.capture());
        Rating saved = captor.getValue();
        assertThat(saved.getRaterProfileId()).isEqualTo(caller);
        assertThat(saved.getSubjectId()).isEqualTo(subject);
        assertThat(saved.getScore()).isEqualTo(4);
        assertThat(saved.getPeriod()).isEqualTo("2026-Q2");
    }

    @Test
    void revisesOwnExistingRating_doesNotCreateSecond() {
        authenticateCaller();
        when(scopeGuard.isNotSelf(subject)).thenReturn(true);
        Rating existing = Rating.create(RatingSubjectType.REPRESENTATIVE, subject, caller, 2, "old", "2026-Q2");
        when(ratingRepository.findBySubjectTypeAndSubjectIdAndRaterProfileIdAndPeriod(
                RatingSubjectType.REPRESENTATIVE, subject, caller, "2026-Q2"))
                .thenReturn(Optional.of(existing));
        when(ratingRepository.save(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        service().submit(request());

        // One-per-person: the rater's OWN row is revised, never a second insert.
        verify(ratingRepository).save(existing);
        verify(ratingRepository, never()).saveAndFlush(any());
        assertThat(existing.getScore()).isEqualTo(4);
        assertThat(existing.getComment()).isEqualTo("good work");
    }

    @Test
    void concurrentDuplicate_surfacesAsConflict() {
        authenticateCaller();
        when(scopeGuard.isNotSelf(subject)).thenReturn(true);
        when(ratingRepository.findBySubjectTypeAndSubjectIdAndRaterProfileIdAndPeriod(
                RatingSubjectType.REPRESENTATIVE, subject, caller, "2026-Q2"))
                .thenReturn(Optional.empty());
        // The DB unique fires on a concurrent double-submit → must become a clean 409, not a 500.
        when(ratingRepository.saveAndFlush(any(Rating.class)))
                .thenThrow(new DataIntegrityViolationException("ux_rating_one_per_rater_subject_period"));

        assertThatThrownBy(() -> service().submit(request()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void electoralScope_notTokenGated_consultsScopeAndElectoralPortsOnly() {
        // Guards against regression of the fence: the service path consults the security/electoral seams
        // (ScopeGuard, RepresentativeQueryApi, ElectoralScopeApi) and NEVER a token balance. There is no
        // token collaborator on RatingService at all (asserted by its constructor signature).
        authenticateCaller();
        lenient().when(scopeGuard.isNotSelf(any())).thenReturn(true);
        // A constituency-less rep (empty) carries no electoral gate → the rating proceeds.
        when(representativeQueryApi.constituencyOf(subject)).thenReturn(Optional.empty());
        when(ratingRepository.findBySubjectTypeAndSubjectIdAndRaterProfileIdAndPeriod(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        service().submit(request());

        verify(scopeGuard).isNotSelf(subject);
        verify(representativeQueryApi).constituencyOf(subject);
    }

    @Test
    void blocksRatingOutsideElectoralScope_outOfScope() {
        // The subject rep holds a constituency the rater is NOT an elector of → OUT_OF_SCOPE (D13),
        // resolved purely via the published scope/electoral ports — no token balance is ever read (fence).
        authenticateCaller();
        UUID repConstituency = UUID.randomUUID();
        when(scopeGuard.isNotSelf(subject)).thenReturn(true);
        when(representativeQueryApi.constituencyOf(subject)).thenReturn(Optional.of(repConstituency));
        when(electoralScopeApi.isElectorOf(caller, repConstituency)).thenReturn(false);

        assertThatThrownBy(() -> service().submit(request()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.OUT_OF_SCOPE);

        // The out-of-scope rater never reaches the persistence path.
        verify(ratingRepository, never()).saveAndFlush(any());
        verify(ratingRepository, never()).save(any());
    }

    @Test
    void allowsRatingWithinElectoralScope_persists() {
        // The rater IS an elector of the rep's constituency → the rating proceeds, keyed to the caller.
        authenticateCaller();
        UUID repConstituency = UUID.randomUUID();
        when(scopeGuard.isNotSelf(subject)).thenReturn(true);
        when(representativeQueryApi.constituencyOf(subject)).thenReturn(Optional.of(repConstituency));
        when(electoralScopeApi.isElectorOf(caller, repConstituency)).thenReturn(true);
        when(ratingRepository.findBySubjectTypeAndSubjectIdAndRaterProfileIdAndPeriod(
                RatingSubjectType.REPRESENTATIVE, subject, caller, "2026-Q2"))
                .thenReturn(Optional.empty());
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        service().submit(request());

        verify(electoralScopeApi).isElectorOf(caller, repConstituency);
        verify(ratingRepository).saveAndFlush(any(Rating.class));
    }

    // ---------------------------------------------------------------------------------------------
    // F1: councillor (ward-tier) electoral scope — a councillor holds a WARD, not a constituency, so the
    // gate must be the rater's electoral WARD. Previously these reps were ungated (constituency empty).
    // ---------------------------------------------------------------------------------------------

    @Test
    void blocksRatingCouncillor_outsideElectoralWard_outOfScope() {
        // The subject is a councillor: no constituency, but a ward the rater is NOT an elector of → the
        // ward gate (F1) must deny OUT_OF_SCOPE. This test would FAIL (rating would proceed) under the old
        // constituency-only logic, which silently skipped every councillor.
        authenticateCaller();
        UUID councillorWard = UUID.randomUUID();
        when(scopeGuard.isNotSelf(subject)).thenReturn(true);
        when(representativeQueryApi.constituencyOf(subject)).thenReturn(Optional.empty());
        when(representativeQueryApi.wardOf(subject)).thenReturn(Optional.of(councillorWard));
        when(electoralScopeApi.isElectorOfWard(caller, councillorWard)).thenReturn(false);

        assertThatThrownBy(() -> service().submit(request()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.OUT_OF_SCOPE);

        verify(ratingRepository, never()).saveAndFlush(any());
        verify(ratingRepository, never()).save(any());
    }

    @Test
    void allowsRatingCouncillor_withinElectoralWard_persists() {
        // The rater IS an elector of the councillor's ward → the rating proceeds via the ward gate (F1).
        authenticateCaller();
        UUID councillorWard = UUID.randomUUID();
        when(scopeGuard.isNotSelf(subject)).thenReturn(true);
        when(representativeQueryApi.constituencyOf(subject)).thenReturn(Optional.empty());
        when(representativeQueryApi.wardOf(subject)).thenReturn(Optional.of(councillorWard));
        when(electoralScopeApi.isElectorOfWard(caller, councillorWard)).thenReturn(true);
        when(ratingRepository.findBySubjectTypeAndSubjectIdAndRaterProfileIdAndPeriod(
                RatingSubjectType.REPRESENTATIVE, subject, caller, "2026-Q2"))
                .thenReturn(Optional.empty());
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        service().submit(request());

        verify(electoralScopeApi).isElectorOfWard(caller, councillorWard);
        verify(ratingRepository).saveAndFlush(any(Rating.class));
    }

    @Test
    void allowsRatingSpecialSeatsRep_noGeographicGate_regressionGuard() {
        // A special-seats / nominated rep has NEITHER a constituency NOR a ward → no geographic gate (PRD
        // §22.6). F1 must not regress this: the rating proceeds without any electoral-location check.
        authenticateCaller();
        when(scopeGuard.isNotSelf(subject)).thenReturn(true);
        when(representativeQueryApi.constituencyOf(subject)).thenReturn(Optional.empty());
        when(representativeQueryApi.wardOf(subject)).thenReturn(Optional.empty());
        when(ratingRepository.findBySubjectTypeAndSubjectIdAndRaterProfileIdAndPeriod(
                RatingSubjectType.REPRESENTATIVE, subject, caller, "2026-Q2"))
                .thenReturn(Optional.empty());
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        service().submit(request());

        // Neither electoral check is consulted for a seat-less rep; the rating persists.
        verify(electoralScopeApi, never()).isElectorOf(any(), any());
        verify(electoralScopeApi, never()).isElectorOfWard(any(), any());
        verify(ratingRepository).saveAndFlush(any(Rating.class));
    }

    @Test
    void onSuccess_emitsRatingSubmittedAudit_refsOnly_noPii() {
        // R-4: a successful submit appends a RATING_SUBMITTED audit event with actor=rater, subject=rated,
        // and a non-PII reason (subjectType:period) — never the score or comment.
        authenticateCaller();
        UUID repConstituency = UUID.randomUUID();
        when(scopeGuard.isNotSelf(subject)).thenReturn(true);
        when(representativeQueryApi.constituencyOf(subject)).thenReturn(Optional.of(repConstituency));
        when(electoralScopeApi.isElectorOf(caller, repConstituency)).thenReturn(true);
        when(ratingRepository.findBySubjectTypeAndSubjectIdAndRaterProfileIdAndPeriod(
                RatingSubjectType.REPRESENTATIVE, subject, caller, "2026-Q2"))
                .thenReturn(Optional.empty());
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        service().submit(request());

        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(ev.capture());
        AuditEvent e = ev.getValue();
        assertThat(e.getEventType()).isEqualTo(AuditEventType.RATING_SUBMITTED);
        assertThat(e.getActorPublicId()).isEqualTo(caller);
        assertThat(e.getSubjectPublicId()).isEqualTo(subject);
        assertThat(e.getReasonCode()).isEqualTo("REPRESENTATIVE:2026-Q2");
        // The non-PII reason carries no score/comment.
        assertThat(e.getReasonCode()).doesNotContain("good work");
    }
}
