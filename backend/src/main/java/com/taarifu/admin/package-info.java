/**
 * admin module — the back-office Admin Console API (M14, US-14.1, UC-H06/H07; ARCHITECTURE.md §3.1).
 *
 * <p>Responsibility: the aggregation + identity-administration + system-config surface the Angular admin
 * console needs, all method-secured to {@code ROLE_ADMIN}/{@code ROOT} (deny-by-default). It deliberately
 * does <b>not</b> duplicate the per-module admin CRUD that already lives in those modules (e.g.
 * {@code /admin/tokens} in {@code tokens}); it provides only what is genuinely cross-cutting:</p>
 * <ul>
 *   <li><b>Dashboard stats</b> ({@code /admin/dashboard/stats}) — counts aggregated across modules via the
 *       published {@link com.taarifu.admin.api.spi.ModuleStatsProvider} SPI (each module implements its
 *       own provider; admin never imports another module's internals — ADR-0013 §1, ARCHITECTURE §3.2).</li>
 *   <li><b>User &amp; role management</b> ({@code /admin/users}) — list accounts (masked PII) and grant/
 *       revoke roles + suspend/reinstate, delegated to the {@code identity} module through the published
 *       {@link com.taarifu.admin.api.spi.IdentityAdminPort}; conflict-of-interest fenced (D16 — no
 *       self-action).</li>
 *   <li><b>System config</b> ({@code /admin/config} write; {@code /app-config} public boot read) — feature
 *       flags and per-platform app config (mobile min-version / force-update / splash, PRD EI-16); the
 *       admin module owns these tables (Flyway V90).</li>
 * </ul>
 *
 * <p>Isolation: cross-module reads/commands go only through published {@code api} ports/SPIs; other
 * modules are referenced by opaque {@code UUID}. A stub {@link com.taarifu.admin.api.spi.IdentityAdminPort}
 * lets the module boot/build/test before identity wires its real adapter (ARCHITECTURE §7).</p>
 */
package com.taarifu.admin;
