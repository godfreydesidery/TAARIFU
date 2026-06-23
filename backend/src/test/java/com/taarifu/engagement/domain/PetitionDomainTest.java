package com.taarifu.engagement.domain;

import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.Survey;
import com.taarifu.engagement.domain.model.enums.PetitionStatus;
import com.taarifu.engagement.domain.model.enums.PetitionTargetType;
import com.taarifu.engagement.domain.model.enums.SurveyStatus;
import com.taarifu.engagement.domain.model.enums.SurveyType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-domain unit tests for the engagement aggregates' state transitions (CLAUDE.md §10 — unit-test the
 * domain without Spring).
 *
 * <p>WHY these specific cases: they pin the two non-obvious domain invariants this scaffold owns —
 * (1) a petition flips to {@code SUCCEEDED} exactly when its signature count reaches the goal (and only
 * from {@code ACTIVE}, never from {@code DRAFT}); (2) a non-poll survey can never be created binding
 * (the integrity fence must not be reachable by a mislabelled survey). These are the rules the
 * {@code registerSignature}/{@code create} methods enforce locally; if they regress, the binding-action
 * semantics break.</p>
 */
class PetitionDomainTest {

    private static Petition activePetition(int goal) {
        Petition p = Petition.create("Repair Kata road", "Please fix it",
                PetitionTargetType.OFFICE, UUID.randomUUID(), goal, null, UUID.randomUUID(), null);
        p.activate();
        return p;
    }

    @Test
    void signatureCountReachingGoalFlipsActiveToSucceeded() {
        Petition p = activePetition(2);
        assertThat(p.getStatus()).isEqualTo(PetitionStatus.ACTIVE);

        p.registerSignature();
        assertThat(p.getSignatureCount()).isEqualTo(1);
        assertThat(p.getStatus()).isEqualTo(PetitionStatus.ACTIVE);

        p.registerSignature();
        assertThat(p.getSignatureCount()).isEqualTo(2);
        assertThat(p.getStatus()).isEqualTo(PetitionStatus.SUCCEEDED);
    }

    @Test
    void draftPetitionDoesNotAutoSucceedOnSignature() {
        // A DRAFT petition is not publicly visible and must not transition to SUCCEEDED.
        Petition p = Petition.create("X", "Y", PetitionTargetType.OFFICE, UUID.randomUUID(),
                1, null, UUID.randomUUID(), null);
        assertThat(p.getStatus()).isEqualTo(PetitionStatus.DRAFT);
        assertThat(p.isPubliclyVisible()).isFalse();

        p.registerSignature();
        assertThat(p.getSignatureCount()).isEqualTo(1);
        assertThat(p.getStatus()).isEqualTo(PetitionStatus.DRAFT);
    }

    @Test
    void nonPollSurveyCanNeverBeBinding() {
        // Integrity: a SURVEY mislabelled binding must come out non-binding (fence not reachable).
        Survey s = Survey.create("Opinion", null, SurveyType.SURVEY, true,
                null, null, null, null, false, UUID.randomUUID(), null);
        assertThat(s.isBinding()).isFalse();
        assertThat(s.getStatus()).isEqualTo(SurveyStatus.DRAFT);
    }

    @Test
    void bindingPollKeepsBindingFlag() {
        Survey s = Survey.create("Binding poll", null, SurveyType.POLL, true,
                null, null, null, null, false, UUID.randomUUID(), null);
        assertThat(s.isBinding()).isTrue();
    }
}
