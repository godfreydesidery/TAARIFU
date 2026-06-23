package com.taarifu.institutions.application.service;

import com.taarifu.geography.application.service.GeographyQueryService;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.geography.domain.model.enums.LocationType;
import com.taarifu.institutions.api.dto.MyRepresentativesDto;
import com.taarifu.institutions.api.dto.RepresentativeSummaryDto;
import com.taarifu.institutions.application.mapper.InstitutionsMapper;
import com.taarifu.institutions.domain.model.Representative;
import com.taarifu.institutions.domain.model.enums.RepresentativeStatus;
import com.taarifu.institutions.domain.model.enums.RepresentativeType;
import com.taarifu.institutions.domain.repository.ParliamentRepository;
import com.taarifu.institutions.domain.repository.ParliamentRoleRepository;
import com.taarifu.institutions.domain.repository.PoliticalPartyRepository;
import com.taarifu.institutions.domain.repository.RepresentativeRepository;
import com.taarifu.institutions.test.EntityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InstitutionsQueryService} "find my representatives" fan-out (PRD §22.6, UC-C01),
 * Mockito only.
 *
 * <p>Responsibility: proves the find-my-rep composition — the MP is resolved via the ward's current
 * constituency (geography's effective-dated bridge, mocked through its public service), and councillors
 * vs ward executives are split by representative type from the ward's sitting reps. Also proves the
 * graceful-degradation rule: a ward with no current constituency mapping yields a {@code null} MP rather
 * than an error.</p>
 */
class InstitutionsQueryServiceTest {

    private RepresentativeRepository representativeRepository;
    private GeographyQueryService geographyQueryService;
    private InstitutionsQueryService service;

    private Location ward;
    private Constituency constituency;

    @BeforeEach
    void setUp() {
        representativeRepository = mock(RepresentativeRepository.class);
        PoliticalPartyRepository partyRepository = mock(PoliticalPartyRepository.class);
        ParliamentRepository parliamentRepository = mock(ParliamentRepository.class);
        ParliamentRoleRepository parliamentRoleRepository = mock(ParliamentRoleRepository.class);
        geographyQueryService = mock(GeographyQueryService.class);
        // A real mapper so the assertions exercise the actual entity→summary mapping.
        InstitutionsMapper mapper = new InstitutionsMapper();

        service = new InstitutionsQueryService(representativeRepository, partyRepository,
                parliamentRepository, parliamentRoleRepository, geographyQueryService, mapper);

        ward = EntityTestSupport.newWithIds(Location.class, 200L, UUID.randomUUID());
        EntityTestSupport.set(ward, "name", "Mengwe");
        EntityTestSupport.set(ward, "type", LocationType.WARD);
        constituency = EntityTestSupport.newWithIds(Constituency.class, 100L, UUID.randomUUID());
        EntityTestSupport.set(constituency, "name", "Rombo");
    }

    @Test
    void findByWard_returnsMpViaConstituency_andSplitsWardReps() {
        when(geographyQueryService.resolveWardPin(ward.getPublicId()))
                .thenReturn(new GeographyQueryService.WardPin(ward, constituency));

        Representative mp = rep(RepresentativeType.MP);
        Representative councillor = rep(RepresentativeType.COUNCILLOR);
        Representative wardExec = rep(RepresentativeType.WARD_EXEC);

        when(representativeRepository.findByConstituencyAndStatus(
                eq(constituency.getPublicId()), eq(RepresentativeStatus.SITTING)))
                .thenReturn(List.of(mp));
        when(representativeRepository.findByWardAndStatus(
                eq(ward.getPublicId()), eq(RepresentativeStatus.SITTING)))
                .thenReturn(List.of(councillor, wardExec));

        MyRepresentativesDto result = service.findRepresentativesByWard(ward.getPublicId());

        assertThat(result.wardName()).isEqualTo("Mengwe");
        assertThat(result.constituencyName()).isEqualTo("Rombo");
        assertThat(result.mp()).isNotNull();
        assertThat(result.mp().type()).isEqualTo("MP");
        assertThat(result.councillors()).extracting(RepresentativeSummaryDto::type).containsExactly("COUNCILLOR");
        assertThat(result.wardExecutives()).extracting(RepresentativeSummaryDto::type).containsExactly("WARD_EXEC");
    }

    @Test
    void findByWard_withNoConstituencyMapping_yieldsNullMp_notError() {
        // Ward resolves but has no current constituency mapping (graceful degradation, PRD R2).
        when(geographyQueryService.resolveWardPin(ward.getPublicId()))
                .thenReturn(new GeographyQueryService.WardPin(ward, null));
        when(representativeRepository.findByWardAndStatus(
                eq(ward.getPublicId()), eq(RepresentativeStatus.SITTING)))
                .thenReturn(List.of());

        MyRepresentativesDto result = service.findRepresentativesByWard(ward.getPublicId());

        assertThat(result.constituencyId()).isNull();
        assertThat(result.mp()).isNull();
        assertThat(result.councillors()).isEmpty();
        assertThat(result.wardExecutives()).isEmpty();
    }

    private Representative rep(RepresentativeType type) {
        Representative r = EntityTestSupport.newWithIds(Representative.class, 1L, UUID.randomUUID());
        EntityTestSupport.set(r, "type", type);
        EntityTestSupport.set(r, "mandate", type == RepresentativeType.MP
                ? com.taarifu.institutions.domain.model.enums.Mandate.CONSTITUENCY
                : com.taarifu.institutions.domain.model.enums.Mandate.COUNCILLOR_WARD);
        EntityTestSupport.set(r, "status", RepresentativeStatus.SITTING);
        EntityTestSupport.set(r, "legislature",
                com.taarifu.institutions.domain.model.enums.Legislature.UNION_PARLIAMENT);
        return r;
    }
}
