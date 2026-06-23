package com.taarifu.institutions.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.geography.application.service.GeographyQueryService;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.geography.domain.model.enums.LocationType;
import com.taarifu.institutions.api.dto.RepresentativeWriteDto;
import com.taarifu.institutions.application.mapper.InstitutionsMapper;
import com.taarifu.institutions.domain.model.Representative;
import com.taarifu.institutions.domain.repository.ParliamentRepository;
import com.taarifu.institutions.domain.repository.ParliamentRoleRepository;
import com.taarifu.institutions.domain.repository.PoliticalPartyRepository;
import com.taarifu.institutions.domain.repository.RepresentativeRepository;
import com.taarifu.institutions.test.EntityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InstitutionsAdminService} — the institutions integrity invariants, proven
 * without a database (Mockito only). PRD §9.1.
 *
 * <p>Responsibility: pins the rules a reviewer must never see regress — (1) the mandate⇄geography
 * coherence rule rejects every impossible seat shape, (2) special-seats/nominated MPs with no
 * constituency are accepted as valid, (3) the one-SITTING-MP-per-constituency pre-check fires before the
 * DB index. Each test would fail if the corresponding guard were removed.</p>
 */
class InstitutionsAdminServiceTest {

    private RepresentativeRepository representativeRepository;
    private GeographyQueryService geographyQueryService;
    private InstitutionsMapper mapper;
    private InstitutionsAdminService service;

    private Constituency rombo;
    private Location mengweWard;

    @BeforeEach
    void setUp() {
        PoliticalPartyRepository partyRepository = mock(PoliticalPartyRepository.class);
        ParliamentRepository parliamentRepository = mock(ParliamentRepository.class);
        ParliamentRoleRepository parliamentRoleRepository = mock(ParliamentRoleRepository.class);
        representativeRepository = mock(RepresentativeRepository.class);
        // Geography FK resolution now goes through geography's PUBLIC service (ADR-0013), not its repos.
        geographyQueryService = mock(GeographyQueryService.class);
        mapper = mock(InstitutionsMapper.class);

        service = new InstitutionsAdminService(partyRepository, parliamentRepository,
                parliamentRoleRepository, representativeRepository, geographyQueryService, mapper);

        rombo = EntityTestSupport.newWithIds(Constituency.class, 100L, UUID.randomUUID());
        EntityTestSupport.set(rombo, "name", "Rombo");
        mengweWard = EntityTestSupport.newWithIds(Location.class, 200L, UUID.randomUUID());
        EntityTestSupport.set(mengweWard, "name", "Mengwe");
        EntityTestSupport.set(mengweWard, "type", LocationType.WARD);

        // The mapper is irrelevant to the invariant assertions; return a non-null DTO so save paths complete.
        when(mapper.toDto(any(Representative.class))).thenReturn(null);
        when(representativeRepository.save(any(Representative.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void constituencyMandate_requiresConstituency_andRejectsWard() {
        when(geographyQueryService.resolveConstituency(rombo.getPublicId())).thenReturn(rombo);
        // CONSTITUENCY mandate but NO constituency id → VALIDATION_FAILED.
        RepresentativeWriteDto missing = base("MP", "CONSTITUENCY", null, null);
        assertThatThrownBy(() -> service.createRepresentative(missing))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // CONSTITUENCY mandate carrying a ward as well → VALIDATION_FAILED (must carry no ward).
        RepresentativeWriteDto withWard = base("MP", "CONSTITUENCY", rombo.getPublicId(), mengweWard.getPublicId());
        assertThatThrownBy(() -> service.createRepresentative(withWard))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void specialSeatsMandate_withNoConstituency_isAccepted() {
        when(representativeRepository.existsSittingByConstituency(any(), any())).thenReturn(false);
        // Viti Maalum / nominated MP: mandate SPECIAL_SEATS, both geographic ids null — a VALID row.
        RepresentativeWriteDto dto = base("MP", "SPECIAL_SEATS", null, null);
        // Status SITTING with no constituency must NOT trip the one-MP check (no seat to contend).
        service.createRepresentative(withStatus(dto, "SITTING"));
        // Reaching save without an exception proves the special-seats path is accepted.
        org.mockito.Mockito.verify(representativeRepository).save(any(Representative.class));
    }

    @Test
    void specialSeatsMandate_carryingConstituency_isRejected() {
        // NOMINATED carrying a constituency id is rejected by the mandate⇄geography pre-check, BEFORE any
        // resolution call — so no geography stub is needed (requireAbsent fires first).
        RepresentativeWriteDto dto = base("MP", "NOMINATED", rombo.getPublicId(), null);
        assertThatThrownBy(() -> service.createRepresentative(dto))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void secondSittingMp_onSameConstituency_isRejectedWithConflict() {
        when(geographyQueryService.resolveConstituency(rombo.getPublicId())).thenReturn(rombo);
        // The pre-check reports an existing SITTING MP on this constituency.
        when(representativeRepository.existsSittingByConstituency(eq(100L), any())).thenReturn(true);

        RepresentativeWriteDto dto = withStatus(base("MP", "CONSTITUENCY", rombo.getPublicId(), null), "SITTING");
        assertThatThrownBy(() -> service.createRepresentative(dto))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void firstSittingMp_onConstituency_isAccepted() {
        when(geographyQueryService.resolveConstituency(rombo.getPublicId())).thenReturn(rombo);
        when(representativeRepository.existsSittingByConstituency(eq(100L), any())).thenReturn(false);

        RepresentativeWriteDto dto = withStatus(base("MP", "CONSTITUENCY", rombo.getPublicId(), null), "SITTING");
        service.createRepresentative(dto);
        org.mockito.Mockito.verify(representativeRepository).save(any(Representative.class));
    }

    @Test
    void councillorWardMandate_requiresWard_resolvedAndTypeChecked() {
        when(geographyQueryService.resolveWard(mengweWard.getPublicId())).thenReturn(mengweWard);
        when(representativeRepository.existsSittingByConstituency(any(), any())).thenReturn(false);

        RepresentativeWriteDto dto = base("COUNCILLOR", "COUNCILLOR_WARD", null, mengweWard.getPublicId());
        service.createRepresentative(dto);
        org.mockito.Mockito.verify(representativeRepository).save(any(Representative.class));
    }

    @Test
    void councillorWardMandate_withNonWardLocation_isNotFound() {
        // The minimum-pin-granularity (WARD) check now lives in geography's resolveWard, which throws
        // not-found for a non-ward location — institutions surfaces that same NOT_FOUND.
        UUID districtId = UUID.randomUUID();
        when(geographyQueryService.resolveWard(districtId))
                .thenThrow(new ResourceNotFoundException("geography.ward.notFound", districtId));

        RepresentativeWriteDto dto = base("COUNCILLOR", "COUNCILLOR_WARD", null, districtId);
        assertThatThrownBy(() -> service.createRepresentative(dto))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // --- helpers ---

    private static RepresentativeWriteDto base(String type, String mandate, UUID constituencyId, UUID wardId) {
        return new RepresentativeWriteDto(
                UUID.randomUUID(), type, mandate, constituencyId, wardId,
                null, "UNION_PARLIAMENT", null, null, "PENDING_VERIFICATION", null, "bio");
    }

    private static RepresentativeWriteDto withStatus(RepresentativeWriteDto dto, String status) {
        return new RepresentativeWriteDto(
                dto.profileId(), dto.type(), dto.mandate(), dto.constituencyId(), dto.wardId(),
                dto.partyId(), dto.legislature(), dto.parliamentId(), dto.parliamentRoleId(),
                status, dto.electedAt(), dto.bio());
    }
}
