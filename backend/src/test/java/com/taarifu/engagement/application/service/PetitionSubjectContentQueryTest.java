package com.taarifu.engagement.application.service;

import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.enums.PetitionTargetType;
import com.taarifu.engagement.domain.repository.PetitionRepository;
import com.taarifu.moderation.api.FlagSubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PetitionSubjectContentQuery} — engagement's content port that lets moderation's
 * auto-assist screen score a flagged petition (ADR-0018; ADR-0013 §4c; CLAUDE.md §10).
 *
 * <p>Responsibility: proves (a) the port declares {@link FlagSubjectType#PETITION} so moderation's registry
 * dispatches petition flags to it; (b) it returns the petition's title + body as one scorable document
 * (so harmful content in either field is screened); (c) a missing/soft-deleted petition resolves to empty —
 * so the auto-assist screen is skipped and the flagged item still goes to a human (the EI-18 floor); and
 * (d) the returned text carries no PII (no creator id).</p>
 */
@ExtendWith(MockitoExtension.class)
class PetitionSubjectContentQueryTest {

    @Mock
    private PetitionRepository petitions;

    private PetitionSubjectContentQuery query;

    private final UUID petitionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        query = new PetitionSubjectContentQuery(petitions);
    }

    @Test
    void declaresPetitionSubjectType() {
        // The registry key — moderation dispatches PETITION flags here.
        assertThat(query.subjectType()).isEqualTo(FlagSubjectType.PETITION);
    }

    @Test
    void returnsTitleAndBodyAsOneScorableDocument() {
        UUID creator = UUID.randomUUID();
        Petition petition = Petition.create("Maji safi sasa", "Tunaomba maji safi katika kata yetu.",
                PetitionTargetType.OFFICE, UUID.randomUUID(), 100, null, creator, null);
        when(petitions.findByPublicId(petitionId)).thenReturn(Optional.of(petition));

        Optional<String> text = query.contentTextOf(petitionId);

        assertThat(text).isPresent();
        assertThat(text.get()).contains("Maji safi sasa");                       // title scored
        assertThat(text.get()).contains("Tunaomba maji safi katika kata yetu."); // body scored
        // 🔒 No PII: the creator account id is never in the scored text (data minimisation, PRD §18).
        assertThat(text.get()).doesNotContain(creator.toString());
    }

    @Test
    void missingPetition_resolvesEmpty_screenSkipped() {
        // EI-18: an absent/soft-deleted petition → empty → the auto-assist screen is skipped and the flagged
        // item still goes to a human moderator (the absence of content never blocks flagging).
        when(petitions.findByPublicId(petitionId)).thenReturn(Optional.empty());

        assertThat(query.contentTextOf(petitionId)).isEmpty();
    }
}
