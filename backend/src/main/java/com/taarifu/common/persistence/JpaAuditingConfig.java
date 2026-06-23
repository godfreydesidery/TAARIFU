package com.taarifu.common.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables Spring Data JPA auditing for the whole application (ARCHITECTURE.md §4.2).
 *
 * <p>Responsibility: switches on auditing so {@link com.taarifu.common.domain.model.BaseEntity}'s
 * {@code @CreatedDate}/{@code @CreatedBy}/{@code @LastModifiedDate}/{@code @LastModifiedBy} columns
 * are populated automatically. The acting-user resolution is provided by
 * {@link AuditorAwareImpl} (referenced by bean name {@code auditorAware}).</p>
 *
 * <p>WHY a dedicated {@code @Configuration} (rather than annotating {@code TaarifuApplication}):
 * keeping it separate lets {@code @DataJpaTest} slices opt in/out cleanly and keeps the entry point
 * free of cross-cutting concerns (CLAUDE.md §8).</p>
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {
}
