package com.taarifu;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test-only Flyway strategy that <b>cleans the schema before migrating</b>, for the integration tests that
 * boot the real migrated schema under {@code ddl-auto=validate} (the production configuration —
 * ADR-0005/0009).
 *
 * <p><b>The harness defect this fixes.</b> The whole integration suite shares a single static PostGIS
 * Testcontainer (see {@link AbstractPostgisIntegrationTest}) for speed. Most tests run the default
 * {@code test} profile with Hibernate {@code create-drop}, which leaves entity tables in the {@code public}
 * schema but <b>no {@code flyway_schema_history} table</b>. When a Flyway+validate test then boots against
 * that same container, Flyway finds a non-empty schema with no history table and <b>fail-safes</b>:
 * {@code "Found non-empty schema(s) \"public\" but no schema history table"} — so the context fails to load
 * and every Flyway+validate test that happens to run after a create-drop test errors. That ordering
 * coupling (not a product bug) is exactly the cross-test leakage the harness must remove.</p>
 *
 * <p><b>Why clean-then-migrate (not {@code baseline-on-migrate}).</b> Baselining would accept the
 * create-drop leftovers as a starting point and skip the migrations, so {@code validate} would then run
 * against an entity-derived schema — defeating the entire point of these tests (proving the <i>migrations</i>
 * match the entities). {@link org.flywaydb.core.Flyway#clean() clean()} drops everything first, so
 * {@link org.flywaydb.core.Flyway#migrate() migrate()} rebuilds the schema purely from the V-scripts every
 * time — deterministic and order-independent, whatever a prior create-drop test left behind. {@code clean}
 * is enabled for these tests via {@code spring.flyway.clean-disabled=false} in their property source; it is
 * a TEST-ONLY setting and never reaches a real environment (production keeps Flyway clean disabled).</p>
 *
 * <p>Import this config on a Flyway+validate test ({@code @Import(FlywayCleanMigrateTestConfig.class)}) and
 * set {@code spring.flyway.clean-disabled=false} alongside the {@code enabled/validate} properties.</p>
 */
@TestConfiguration
public class FlywayCleanMigrateTestConfig {

    /**
     * Replaces the default migrate-only strategy with clean-then-migrate so the schema is rebuilt from the
     * V-scripts on every context start, ignoring any create-drop residue in the shared container.
     *
     * @return a strategy that cleans then migrates.
     */
    @Bean
    FlywayMigrationStrategy cleanMigrateStrategy() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
