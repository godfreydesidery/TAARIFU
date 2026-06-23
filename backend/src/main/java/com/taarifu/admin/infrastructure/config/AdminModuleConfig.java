package com.taarifu.admin.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * Module wiring for {@code admin} (ARCHITECTURE.md §3.3 {@code infrastructure.config}).
 *
 * <p>Responsibility: a placeholder for admin-module bean wiring. The cross-module dependencies the admin
 * surface needs are satisfied by the callees' published {@code api} ports, injected as interfaces (ADR-0013
 * §1): the reporting read port ({@link com.taarifu.reporting.api.ReportQueryApi}) and the identity user-admin
 * ports ({@link com.taarifu.identity.api.UserAdminQueryApi} / {@link com.taarifu.identity.api.UserAdminApi}),
 * each implemented by a {@code @Service} in the owning module — so no stub or fallback bean is needed here
 * (the owning modules always register the real implementation in the full backend context, exactly as
 * {@code ReportsAdminService} consumes reporting).</p>
 *
 * <p>The {@code List<ModuleStatsProvider>} the dashboard consumes needs no bean here either: Spring injects
 * an empty list when no module has published a provider yet, and the real provider beans register themselves
 * in their own modules.</p>
 */
@Configuration
public class AdminModuleConfig {
}
