package com.taarifu.search.application.mapper;

import com.taarifu.search.api.dto.SearchResultDto;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.repository.SearchResultProjection;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SearchMapper} — locale-aware snippet resolution and the dropping of internal gating
 * fields from the public result (ADR-0017 §4; ADR-0010 SW-default).
 */
class SearchMapperTest {

    private final SearchMapper mapper = new SearchMapper();

    private SearchResultProjection projection(String sw, String en) {
        return new SearchResultProjection() {
            @Override public String getEntityType() { return SearchEntityType.ISSUE_CATEGORY.name(); }
            @Override public UUID getEntityPublicId() { return UUID.randomUUID(); }
            @Override public String getTitle() { return "Maji"; }
            @Override public String getSnippetSw() { return sw; }
            @Override public String getSnippetEn() { return en; }
            @Override public UUID getAreaId() { return null; }
            @Override public UUID getCategoryId() { return null; }
            @Override public double getRank() { return 0.42; }
        };
    }

    @Test
    void swahiliLocale_picksTheSwahiliSnippet() {
        SearchResultDto dto = mapper.toResult(projection("Masuala ya maji", "Water issues"), Locale.forLanguageTag("sw"));
        assertThat(dto.snippet()).isEqualTo("Masuala ya maji");
        assertThat(dto.entityType()).isEqualTo(SearchEntityType.ISSUE_CATEGORY);
        assertThat(dto.rank()).isEqualTo(0.42);
    }

    @Test
    void englishLocale_picksTheEnglishSnippet() {
        SearchResultDto dto = mapper.toResult(projection("Masuala ya maji", "Water issues"), Locale.ENGLISH);
        assertThat(dto.snippet()).isEqualTo("Water issues");
    }

    @Test
    void swahiliLocale_fallsBackToEnglishWhenNoSwahiliSnippet() {
        SearchResultDto dto = mapper.toResult(projection(null, "Water issues"), Locale.forLanguageTag("sw"));
        assertThat(dto.snippet()).isEqualTo("Water issues");
    }

    @Test
    void noSnippetsAtAll_yieldsNullSnippet_notAnError() {
        SearchResultDto dto = mapper.toResult(projection(null, null), Locale.forLanguageTag("sw"));
        assertThat(dto.snippet()).isNull();
    }
}
