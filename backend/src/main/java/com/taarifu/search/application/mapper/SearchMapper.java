package com.taarifu.search.application.mapper;

import com.taarifu.search.api.dto.SearchResultDto;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.repository.SearchResultProjection;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Maps a native-query {@link SearchResultProjection} to the public {@link SearchResultDto}, resolving the
 * snippet to the caller's locale (ADR-0017 §4; ADR-0010 SW-default localisation).
 *
 * <p>Responsibility: the boundary translation — pick the locale-appropriate snippet (Swahili default, English
 * fallback) and turn the stored {@code entity_type} name back into the typed {@link SearchEntityType}. Kept a
 * hand-written {@code @Component} (not MapStruct) because the locale-pick is conditional logic MapStruct would
 * not express cleanly, and because the gating fields ({@code visibility}/{@code authoredByAccountId}) are
 * deliberately dropped here so they never reach a client (PRD §18).</p>
 */
@Component
public class SearchMapper {

    /** ISO-639 code for Swahili — the platform default content language (ADR-0010). */
    private static final String SWAHILI = "sw";

    /**
     * Converts one FTS hit to a result DTO with a locale-resolved snippet.
     *
     * @param projection the native query row (never {@code null}).
     * @param locale     the caller's resolved request locale (drives SW/EN snippet choice).
     * @return the public result DTO (internal gating fields omitted).
     */
    public SearchResultDto toResult(SearchResultProjection projection, Locale locale) {
        return new SearchResultDto(
                SearchEntityType.valueOf(projection.getEntityType()),
                projection.getEntityPublicId(),
                projection.getTitle(),
                resolveSnippet(projection, locale),
                projection.getAreaId(),
                projection.getCategoryId(),
                projection.getRank());
    }

    /**
     * Picks the snippet for the locale: Swahili when the request locale is Swahili (or when no English snippet
     * exists), otherwise English; falls back to whichever is present so a result is never snippet-less when one
     * language was supplied.
     *
     * @param projection the row carrying both localised snippets (either may be {@code null}).
     * @param locale     the caller's resolved locale.
     * @return the chosen snippet, or {@code null} if the owner supplied neither.
     */
    private String resolveSnippet(SearchResultProjection projection, Locale locale) {
        boolean swahili = locale != null && SWAHILI.equalsIgnoreCase(locale.getLanguage());
        String sw = projection.getSnippetSw();
        String en = projection.getSnippetEn();
        if (swahili) {
            return sw != null ? sw : en;
        }
        return en != null ? en : sw;
    }
}
