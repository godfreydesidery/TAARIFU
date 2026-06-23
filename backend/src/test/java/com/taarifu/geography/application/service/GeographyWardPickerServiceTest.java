package com.taarifu.geography.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.geography.api.dto.WardSummaryDto;
import com.taarifu.geography.application.mapper.GeographyMapper;
import com.taarifu.geography.domain.repository.ConstituencyRepository;
import com.taarifu.geography.domain.repository.LocationRepository;
import com.taarifu.geography.domain.repository.WardConstituencyRepository;
import com.taarifu.geography.domain.repository.projection.WardSummaryProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests (no Docker) for {@link GeographyQueryService}'s manual ward-picker query logic —
 * the parts of {@code searchWards} that are independent of the database (PRD §9.0, §15).
 *
 * <p>Responsibility: pins the search behaviour the service owns: (a) a blank/{@code null} query
 * short-circuits to an empty page <b>without touching the repository</b> (a picker must not pull the
 * national ward table on an empty box, PRD §15); (b) the prefix handed to the repository is lowercased,
 * has its {@code LIKE} metacharacters ({@code %}, {@code _}, {@code \}) escaped, and a trailing {@code %}
 * appended for a prefix match; and (c) the mapped projection becomes the lean {@link WardSummaryDto}. The
 * district-existence / wrong-level guards and the closure join are covered end-to-end against a real
 * database in {@code WardPickerReadIntegrationTest} (they need real {@code Location} rows, not mocks). These
 * tests <b>fail if the empty-query guard or the prefix-escaping is removed</b>.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GeographyWardPickerServiceTest {

    @Mock
    private LocationRepository locationRepository;
    @Mock
    private ConstituencyRepository constituencyRepository;
    @Mock
    private WardConstituencyRepository wardConstituencyRepository;
    @Mock
    private ClockPort clock;

    private final GeographyMapper mapper = new GeographyMapper();

    private GeographyQueryService service() {
        return new GeographyQueryService(locationRepository, constituencyRepository,
                wardConstituencyRepository, mapper, clock);
    }

    /** A minimal {@link WardSummaryProjection} stub (no entity construction needed). */
    private static WardSummaryProjection projection(UUID id, String code, String name,
                                                    String councilName, String districtName) {
        return new WardSummaryProjection() {
            @Override public UUID getWardPublicId() { return id; }
            @Override public String getCode() { return code; }
            @Override public String getName() { return name; }
            @Override public String getCouncilName() { return councilName; }
            @Override public String getDistrictName() { return districtName; }
        };
    }

    @Test
    void searchWards_blankQuery_returnsEmptyPage_withoutTouchingRepository() {
        Page<WardSummaryDto> result = service().searchWards("   ", null, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        // The whole point of the guard: no DB hit on an empty box (PRD §15).
        verifyNoInteractions(locationRepository);
    }

    @Test
    void searchWards_nullQuery_returnsEmptyPage_withoutTouchingRepository() {
        Page<WardSummaryDto> result = service().searchWards(null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(locationRepository);
    }

    @Test
    void searchWards_normalisesPrefix_lowercasesEscapesAndAppendsPercent() {
        when(locationRepository.searchWardSummaries(any(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        // A query carrying upper-case and LIKE metacharacters '%' and '_'.
        service().searchWards("Me%n_", null, PageRequest.of(0, 20));

        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        verify(locationRepository).searchWardSummaries(prefix.capture(), isNull(), any(Pageable.class));
        // lowercased, '%'->'\%', '_'->'\_', trailing '%' for prefix match.
        assertThat(prefix.getValue()).isEqualTo("me\\%n\\_%");
    }

    @Test
    void searchWards_unscopedMatch_mapsProjectionToLeanDto() {
        UUID wardId = UUID.randomUUID();
        when(locationRepository.searchWardSummaries(any(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(projection(wardId, "TZ-W-1", "Mengwe", "Rombo DC", "Rombo")),
                        PageRequest.of(0, 20), 1));

        Page<WardSummaryDto> result = service().searchWards("me", null, PageRequest.of(0, 20));

        assertThat(result.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.id()).isEqualTo(wardId);
            assertThat(dto.code()).isEqualTo("TZ-W-1");
            assertThat(dto.name()).isEqualTo("Mengwe");
            assertThat(dto.councilName()).isEqualTo("Rombo DC");
            assertThat(dto.districtName()).isEqualTo("Rombo");
        });
    }
}
