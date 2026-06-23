package com.taarifu.engagement.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.engagement.application.mapper.EngagementMapper;
import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.PetitionSignature;
import com.taarifu.engagement.domain.model.enums.PetitionStatus;
import com.taarifu.engagement.domain.model.enums.PetitionTargetType;
import com.taarifu.engagement.domain.repository.PetitionRepository;
import com.taarifu.engagement.domain.repository.PetitionSignatureRepository;
import com.taarifu.identity.api.ElectoralScopeApi;
import com.taarifu.institutions.api.RepresentativeQueryApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PetitionService} — the binding sign path and its integrity guards (CLAUDE.md
 * §10; the "test that proves the integrity rule" mandate).
 *
 * <p>Mockito-only (no Spring, no DB): the DB unique constraint is integration-tested separately. Here we
 * prove the <b>application-layer</b> halves of the fence: (a) a representative cannot sign a petition
 * targeting themselves (D16 conflict-of-interest → {@link ErrorCode#CONFLICT_OF_INTEREST}, audited);
 * (b) a duplicate sign is rejected by the pre-check ({@link ErrorCode#CONFLICT}) and no signature is
 * saved; (c) a non-ACTIVE petition cannot be signed; and — the keystone — (d) <b>token balance is never
 * consulted</b> (there is no token collaborator on this service at all, which is the fence by
 * construction).</p>
 */
@ExtendWith(MockitoExtension.class)
class PetitionServiceTest {

    @Mock
    private PetitionRepository petitions;
    @Mock
    private PetitionSignatureRepository signatures;
    @Mock
    private ScopeGuard scopeGuard;
    @Mock
    private RepresentativeQueryApi representativeQueryApi;
    @Mock
    private ElectoralScopeApi electoralScopeApi;
    @Mock
    private AuditEventService audit;

    private final EngagementMapper mapper = new EngagementMapper();
    private PetitionService service;

    private final UUID signer = UUID.randomUUID();
    private final UUID petitionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PetitionService(petitions, signatures, mapper, scopeGuard,
                representativeQueryApi, electoralScopeApi, audit);
    }

    private Petition activeOfficePetition() {
        Petition p = Petition.create("Fix road", "body", PetitionTargetType.OFFICE,
                UUID.randomUUID(), 100, null, UUID.randomUUID(), null);
        p.activate();
        return p;
    }

    @Test
    void signHappyPath_incrementsCount_andNeverTouchesTokenBalance() {
        // No token collaborator exists on PetitionService — the integrity fence by construction (PRD §23.5).
        Petition petition = activeOfficePetition();
        when(petitions.findByPublicId(petitionId)).thenReturn(Optional.of(petition));
        when(signatures.existsByPetition_PublicIdAndSignerProfileId(petitionId, signer)).thenReturn(false);

        var dto = service.sign(petitionId, signer, "ndio", false);

        assertThat(dto.signatureCount()).isEqualTo(1);
        verify(signatures).save(any(PetitionSignature.class));
    }

    @Test
    void signBlocksSelfPetition_whenTargetIsSigner_andAudits() {
        // A REPRESENTATIVE-targeted petition whose target is the signer => D16 conflict-of-interest.
        Petition repPetition = Petition.create("About me", "body", PetitionTargetType.REPRESENTATIVE,
                UUID.randomUUID(), 100, null, UUID.randomUUID(), null);
        repPetition.activate();
        when(petitions.findByPublicId(petitionId)).thenReturn(Optional.of(repPetition));
        // isNotSelf returns false => the target IS the caller (self-action).
        when(scopeGuard.isNotSelf(repPetition.getTargetId())).thenReturn(false);

        assertThatThrownBy(() -> service.sign(petitionId, signer, null, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        verify(signatures, never()).save(any());
        verify(audit).record(any());
    }

    @Test
    void signRejectsDuplicate_onePerPerson_preCheck() {
        Petition petition = activeOfficePetition();
        when(petitions.findByPublicId(petitionId)).thenReturn(Optional.of(petition));
        when(signatures.existsByPetition_PublicIdAndSignerProfileId(petitionId, signer)).thenReturn(true);

        assertThatThrownBy(() -> service.sign(petitionId, signer, null, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(signatures, never()).save(any());
    }

    @Test
    void signBlocksOutOfElectoralScope_onRepresentativePetition_andAudits() {
        // A REPRESENTATIVE-targeted petition is constituency-scoped: the signer must be an elector of the
        // target rep's constituency. Here they are NOT → OUT_OF_SCOPE (D13), audited, no signature saved.
        // The resolution uses only the published scope/electoral ports — no token balance is read (fence).
        UUID target = UUID.randomUUID();
        UUID constituency = UUID.randomUUID();
        Petition repPetition = Petition.create("Scoped", "body", PetitionTargetType.REPRESENTATIVE,
                target, 100, null, UUID.randomUUID(), null);
        repPetition.activate();
        when(petitions.findByPublicId(petitionId)).thenReturn(Optional.of(repPetition));
        when(scopeGuard.isNotSelf(target)).thenReturn(true);
        when(signatures.existsByPetition_PublicIdAndSignerProfileId(petitionId, signer)).thenReturn(false);
        when(representativeQueryApi.constituencyOf(target)).thenReturn(Optional.of(constituency));
        when(electoralScopeApi.isElectorOf(signer, constituency)).thenReturn(false);

        assertThatThrownBy(() -> service.sign(petitionId, signer, null, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.OUT_OF_SCOPE);

        verify(signatures, never()).save(any());
        verify(audit).record(any());
    }

    @Test
    void signAllowsInElectoralScope_onRepresentativePetition() {
        // The signer IS an elector of the target rep's constituency → the sign proceeds and counts.
        UUID target = UUID.randomUUID();
        UUID constituency = UUID.randomUUID();
        Petition repPetition = Petition.create("Scoped", "body", PetitionTargetType.REPRESENTATIVE,
                target, 100, null, UUID.randomUUID(), null);
        repPetition.activate();
        when(petitions.findByPublicId(petitionId)).thenReturn(Optional.of(repPetition));
        when(scopeGuard.isNotSelf(target)).thenReturn(true);
        when(signatures.existsByPetition_PublicIdAndSignerProfileId(petitionId, signer)).thenReturn(false);
        when(representativeQueryApi.constituencyOf(target)).thenReturn(Optional.of(constituency));
        when(electoralScopeApi.isElectorOf(signer, constituency)).thenReturn(true);

        var dto = service.sign(petitionId, signer, "ndio", false);

        assertThat(dto.signatureCount()).isEqualTo(1);
        verify(signatures).save(any(PetitionSignature.class));
    }

    @Test
    void signRejectsNonActivePetition() {
        Petition draft = Petition.create("D", "body", PetitionTargetType.OFFICE,
                UUID.randomUUID(), 10, null, UUID.randomUUID(), null);
        assertThat(draft.getStatus()).isEqualTo(PetitionStatus.DRAFT);
        when(petitions.findByPublicId(petitionId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.sign(petitionId, signer, null, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(signatures, never()).save(any());
    }

    @Test
    void createBlocksPetitionAgainstSelf() {
        UUID target = UUID.randomUUID();
        when(scopeGuard.isNotSelf(target)).thenReturn(false);

        assertThatThrownBy(() -> service.create("t", "b", "REPRESENTATIVE", target, 10, null, signer))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        verify(petitions, never()).save(any());
    }

    @Test
    void createRejectsUnknownTargetType() {
        // parseTargetType must reject garbage before any self-check or save.
        assertThatThrownBy(() -> service.create("t", "b", "MAYOR", UUID.randomUUID(), 10, null, signer))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void createPersistsDraft_forValidOfficeTarget() {
        UUID target = UUID.randomUUID();
        when(scopeGuard.isNotSelf(target)).thenReturn(true);

        var dto = service.create("Title", "Body", "OFFICE", target, 50, null, signer);

        assertThat(dto.status()).isEqualTo(PetitionStatus.DRAFT.name());
        ArgumentCaptor<Petition> saved = ArgumentCaptor.forClass(Petition.class);
        verify(petitions).save(saved.capture());
        assertThat(saved.getValue().getTargetType()).isEqualTo(PetitionTargetType.OFFICE);
        assertThat(saved.getValue().getSignatureGoal()).isEqualTo(50);
        // The creator reference is the caller's id (taken from CurrentUser, never the body).
        assertThat(saved.getValue().getCreatorProfileId()).isEqualTo(signer);
    }

    @Test
    void listPublicExcludesDrafts_byStatusFilter() {
        // The service must query only the public (non-DRAFT) statuses; proven by the captured arg.
        when(petitions.findByStatusIn(any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        service.listPublic(org.springframework.data.domain.PageRequest.of(0, 20));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<PetitionStatus>> statuses =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(petitions).findByStatusIn(statuses.capture(), any());
        assertThat(statuses.getValue()).doesNotContain(PetitionStatus.DRAFT);
    }

    @Test
    void getThrowsNotFound_whenMissing() {
        when(petitions.findByPublicId(petitionId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(petitionId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        // anyString() guard keeps the import used if signature pre-check is reorganised.
        verify(signatures, never()).existsByPetition_PublicIdAndSignerProfileId(any(), any());
    }
}
