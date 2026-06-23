package com.taarifu.reporting.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.reporting.api.dto.CreateIssueCategoryDto;
import com.taarifu.reporting.application.mapper.ReportingMapper;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.repository.IssueCategoryRepository;
import com.taarifu.reporting.test.ReportingTestFixtures;
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
 * Unit tests for {@link IssueCategoryService} CRUD integrity (PRD §9.1, Appendix D; UC-B14).
 *
 * <p>Responsibility: pins duplicate-code rejection, enum-token validation, and not-found semantics — the
 * data-integrity rules the Admin CRUD must keep. Mockito only.</p>
 */
class IssueCategoryServiceTest {

    private IssueCategoryRepository categoryRepository;
    private IssueCategoryService service;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(IssueCategoryRepository.class);
        service = new IssueCategoryService(categoryRepository, new ReportingMapper());
        when(categoryRepository.save(any(IssueCategory.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_duplicateCode_isConflict() {
        when(categoryRepository.existsByCode("WATER_SANITATION")).thenReturn(true);
        CreateIssueCategoryDto request = new CreateIssueCategoryDto("WATER_SANITATION", "Maji", null,
                "SECTOR_UTILITY", 2880, 20160, false, false, "PUBLIC", "water-drop");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void create_badRoutingToken_isBadRequest() {
        when(categoryRepository.existsByCode(any())).thenReturn(false);
        CreateIssueCategoryDto request = new CreateIssueCategoryDto("ROADS", "Barabara", null,
                "NOT_A_LEVEL", 4320, 43200, false, false, "PUBLIC", "road");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void create_badVisibilityToken_isBadRequest() {
        when(categoryRepository.existsByCode(any())).thenReturn(false);
        CreateIssueCategoryDto request = new CreateIssueCategoryDto("ROADS", "Barabara", null,
                "COUNCIL", 4320, 43200, false, false, "SECRET", "road");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void create_validWithParent_succeeds() {
        IssueCategory parent = ReportingTestFixtures.publicCategory("HEALTH");
        when(categoryRepository.existsByCode(any())).thenReturn(false);
        when(categoryRepository.findByPublicId(parent.getPublicId())).thenReturn(Optional.of(parent));
        CreateIssueCategoryDto request = new CreateIssueCategoryDto("HEALTH_NEGLIGENCE", "Uzembe",
                parent.getPublicId(), "COUNCIL", 2880, 30240, true, false, "PRIVATE", "stethoscope");

        var dto = service.create(request);

        assertThat(dto.code()).isEqualTo("HEALTH_NEGLIGENCE");
        assertThat(dto.parentId()).isEqualTo(parent.getPublicId());
        assertThat(dto.sensitive()).isTrue();
        verify(categoryRepository).save(any(IssueCategory.class));
    }

    @Test
    void get_missing_isNotFound() {
        UUID missing = UUID.randomUUID();
        when(categoryRepository.findByPublicId(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(missing))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
