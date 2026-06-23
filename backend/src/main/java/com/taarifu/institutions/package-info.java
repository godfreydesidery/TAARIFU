/**
 * institutions module — political parties, parliament terms/roles, and representatives (PRD §9.1, §22.6;
 * ARCHITECTURE.md §3.1, M2).
 *
 * <p>Responsibility: the verified civic-leadership directory. It models {@code PoliticalParty},
 * {@code Parliament}/{@code ParliamentRole}, and the central {@code Representative} (MP/Councillor/
 * ward-exec) with {@code mandate} (constituency / special-seats / nominated / councillor-ward) and
 * {@code legislature} (Union Parliament / Zanzibar HoR). It powers the public reads: "find my
 * representatives" (Ward→Constituency→MP plus ward Councillor/exec), the representative profile, the
 * directory/search, and the party &amp; parliament directories; plus admin (ROLE_ADMIN) CRUD.</p>
 *
 * <h2>Layering (ARCHITECTURE.md §3.3)</h2>
 * <ul>
 *   <li>{@code api} — controllers (public reads + admin writes), DTOs, events.</li>
 *   <li>{@code application} — read/write services (transaction boundary + integrity invariants) + mapper.</li>
 *   <li>{@code domain} — JPA entities (extending {@code common} {@code BaseEntity}), enums, repositories.</li>
 *   <li>{@code infrastructure} — module configuration.</li>
 * </ul>
 *
 * <h2>Cross-module discipline (ARCHITECTURE.md §3.2)</h2>
 * <p>Depends on {@code common} (kernel) and {@code geography} (foundation — FK-references
 * {@code Constituency}/{@code Location}). The link to an identity account is the Profile's public id
 * ({@code Representative.profileId} UUID) only — never an identity import. No dependency on any feature
 * module being built in parallel.</p>
 *
 * <h2>Integrity invariants</h2>
 * <ul>
 *   <li>One {@code SITTING} constituency-MP per constituency (DB partial-unique index + service pre-check).</li>
 *   <li>Mandate ⇄ geography coherence (constituency-only / ward-only / neither) (service + DB CHECK).</li>
 *   <li>Single current parliament term per legislature (service + DB partial-unique index).</li>
 *   <li>{@code FORMER} representatives retained, never deleted (civic/accountability record survives).</li>
 * </ul>
 */
package com.taarifu.institutions;
