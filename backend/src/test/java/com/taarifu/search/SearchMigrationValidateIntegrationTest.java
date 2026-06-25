package com.taarifu.search;

import com.taarifu.search.domain.model.SearchDocument;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import com.taarifu.search.domain.repository.SearchDocumentRepository;
import com.taarifu.search.domain.repository.SearchResultProjection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration + behaviour test for the search module against a real Postgres (ADR-0017; ARCHITECTURE §4.1).
 *
 * <p>Responsibility: the production-faithful path — run <b>all</b> Flyway migrations (…V146) against a real
 * PostGIS container and start Hibernate in {@code validate} mode, so the context only loads if every entity
 * (including {@code search_document}) exactly agrees with its DDL. It then proves the load-bearing pieces that
 * a pure unit test cannot — that the DB-owned {@code GENERATED … tsvector} column actually indexes the row, that
 * {@code websearch_to_tsquery('simple', …)} matches it and ranks it, that the <b>visibility predicate</b> hides
 * a {@code STAFF} row from a non-staff search but shows it to staff, and that {@code hideByAuthor} flips an
 * author's public rows. WHY a dedicated test (the shared {@code test} profile disables Flyway): without it, a
 * drift in V146 or a broken generated-column expression would not surface until production boot.</p>
 *
 * <p>WHY its own pristine container: Flyway needs an empty schema to migrate from scratch; this test owns a
 * dedicated PostGIS container so V1…V146 run cleanly (the same rationale as the other module migration tests).</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class SearchMigrationValidateIntegrationTest {

    /** A pristine PostGIS container owned solely by this test so Flyway migrates from an empty schema. */
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("taarifu_search_migr")
                    .withUsername("taarifu")
                    .withPassword("taarifu");

    static {
        POSTGIS.start();
    }

    @Autowired
    private SearchDocumentRepository repository;

    /** Wires this test's dedicated container and an empty CORS allow-list into the context. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("taarifu.security.cors.allowed-origins", () -> "");
    }

    @Test
    void migrationValidates_andGeneratedTsvectorMatchesTheFtsQuery() {
        // Reaching here means Hibernate validate passed against the Flyway schema for ALL entities, incl.
        // search_document — i.e. V146 matches SearchDocument exactly (the unmapped generated column is tolerated).
        UUID repId = UUID.randomUUID();
        repository.saveAndFlush(SearchDocument.project(SearchEntityType.REPRESENTATIVE, repId,
                "Diwani wa Kata ya Maji", "Mwakilishi wa masuala ya maji", "Councillor for water",
                "maji,water,kata", null, null, SearchVisibility.PUBLIC, null));

        // The DB-owned GENERATED tsvector must be searchable by the matching 'simple' websearch query.
        Page<SearchResultProjection> hits =
                repository.search("maji", null, null, null, false, PageRequest.of(0, 20));

        assertThat(hits.getContent()).hasSize(1);
        SearchResultProjection hit = hits.getContent().get(0);
        assertThat(hit.getEntityType()).isEqualTo(SearchEntityType.REPRESENTATIVE.name());
        assertThat(hit.getEntityPublicId()).isEqualTo(repId);
        assertThat(hit.getRank()).isGreaterThan(0.0);
    }

    @Test
    void visibilityGate_hidesStaffRowsFromNonStaff_butShowsThemToStaff() {
        repository.saveAndFlush(SearchDocument.project(SearchEntityType.PUBLIC_REPORT, UUID.randomUUID(),
                "Ripoti ya siri ya barabara", "taarifa nyeti", "sensitive report",
                "barabara,siri", null, null, SearchVisibility.STAFF, null));

        // Non-staff (includeStaff=false): the STAFF row is FILTERED OUT of the result set (not 403'd) — §18.
        Page<SearchResultProjection> guestHits =
                repository.search("barabara siri", null, null, null, false, PageRequest.of(0, 20));
        assertThat(guestHits.getContent()).isEmpty();

        // Staff (includeStaff=true): the same row IS returned.
        Page<SearchResultProjection> staffHits =
                repository.search("barabara siri", null, null, null, true, PageRequest.of(0, 20));
        assertThat(staffHits.getContent()).hasSize(1);
    }

    @Test
    @Transactional // hideByAuthor is a @Modifying bulk UPDATE — it needs an active tx (as in the prod handler).
    void hideByAuthor_flipsTheAuthorsPublicRowsToStaff() {
        UUID author = UUID.randomUUID();
        repository.saveAndFlush(SearchDocument.project(SearchEntityType.ANNOUNCEMENT, UUID.randomUUID(),
                "Tangazo la mwandishi", "ujumbe wa tangazo", "announcement body",
                "tangazo,mwandishi", null, null, SearchVisibility.PUBLIC, author));

        int hidden = repository.hideByAuthor(author);
        assertThat(hidden).isEqualTo(1);

        // After hiding, the (now STAFF) row no longer appears in a non-staff search.
        Page<SearchResultProjection> guestHits =
                repository.search("tangazo mwandishi", null, null, null, false, PageRequest.of(0, 20));
        assertThat(guestHits.getContent()).isEmpty();

        // Idempotent: a second hide affects 0 additional rows (already STAFF).
        assertThat(repository.hideByAuthor(author)).isZero();
    }
}
