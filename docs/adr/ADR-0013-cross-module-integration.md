# ADR-0013: Cross-module integration patterns — published api-package query ports for synchronous reads, events for async, and the ArchUnit rule that permits `api → api` while keeping `domain`/`infrastructure` encapsulated

**Status:** Accepted · 2026-06-23 · Solution Architect (David Okello)
**Extends:** ADR-0002 (modular monolith), ADR-0003 (package-by-feature + the boundary `ModuleBoundaryTest`), ADR-0004 (ports & adapters), ADR-0008 (single envelope + transactional outbox). ADR-0003 drew the boundaries and made them mechanically enforced; this ADR decides the **canonical way sibling feature modules integrate across those boundaries** now that all 12 modules exist on `develop` and reference each other by opaque public `UUID` with `// TODO(wiring)`.
**Grounding:** PRD §16 (architecture; extract-to-service), §17 (envelope; ids), §23.5 / D18 (integrity fence — tokens never gate binding actions), §24.3 (multisectoral one-owner+collaborators), §9.0 (multi-location; voter-ID-authoritative electoral; effective-dated ward→constituency), D13 (electoral scope), D16 (no self-action), D21 (routing → responder OWNER). ARCHITECTURE.md §3 (module map, dependency rule, internal layering), §3.4 (enforcement), §8 (outbox), §10 (extract-to-service); CLAUDE.md §3 (SOLID/KISS/clean boundaries), §8 (no entity leaks), §12 (guardrails).
**Companion (precedent in code):** `tokens.api.TokenLedgerApi` — the first published in-process query/command port; this ADR generalises that shape into the house pattern.

## Context

The 12 modules were each built in isolation and **already reference siblings the right way in spirit** — by another module's public `UUID`, never by FK, never by importing its `domain`/`infrastructure` — with the actual call deferred as `// TODO(wiring)` (50+ markers: `RatingService` electoral scope, `ResponderAdminService.assignResponder`, `moderation` subject resolution, `PetitionSignature` electoral scope, `RoutingRule`/`Responder` category validation, etc.). What is **not yet decided** is the *one canonical mechanism* every wiring step uses. Without it, engineers will improvise three different ways to call across a boundary, the cycle-free dependency graph (ARCHITECTURE §3.2) will rot, and the `ModuleBoundaryTest` — which today only forbids the kernel from depending on features, thin-controller, vendor-free-ports, and no-entity-leak — does **not yet** assert the cross-module `domain`/`infrastructure` rule it promises in its own Javadoc ("*As feature modules are added, cross-module `domain`/`infrastructure` import rules will be tightened here*").

Three forces must be reconciled:

1. **The dependency rule must hold (ARCHITECTURE §3.2):** feature modules may depend on foundation modules' **public api packages only**; no cycles; no module imports another's `domain`/`infrastructure`; entities never leak.
2. **Synchronous reads are unavoidable in the request path.** "Is this category valid?", "what is this representative's constituency?", "does this report exist?", "who authored this comment?" must be answered *before* the citizen's write commits — they cannot wait for an async event. These are **in-process method calls**, not network calls (we are a monolith — KISS), and must not pull in the callee's internals.
3. **Side effects must not block the citizen (ADR-0008, D18).** Fan-out, notifications, SLA clocks, search indexing, and *any* cross-module write must ride the **transactional outbox**, which is **not yet built**. Those integrations stay `// TODO(wiring)` until the outbox increment; this ADR must not pretend they are available.

## Decision

### 1. Synchronous cross-module reads go through the callee's **published api-package query port** (the `*Api` / `*QueryApi` pattern)

A module that needs a synchronous answer from a sibling depends on, and calls, a **plain interface published in the callee's `com.taarifu.<callee>.api` package** — the same shape as the existing `tokens.api.TokenLedgerApi`. Rules:

- The port lives **directly in `<module>.api`** (not `api.controller`/`api.dto`), is a **plain Java interface**, and exposes **only public DTOs/records, enums, and `UUID`s** as parameters and return types. Never an entity, never a repository, never an `Optional<Entity>`, never a vendor type.
- Its implementation lives in the callee's `application.service` layer (a `@Service` `@Transactional(readOnly = true)` for reads), wired by Spring. The **caller injects the interface**, never the impl.
- The callee owns the contract: it validates existence/scope, maps to a DTO, and returns. The caller treats the result as opaque truth and **does not** reach past it.
- **Naming:** `<Concept>QueryApi` for read-only lookups (e.g. `RepresentativeQueryApi`, `IssueCategoryQueryApi`, `ElectoralScopeApi`, `ReportQueryApi`, `SubjectAuthorQueryApi`); the existing command-bearing `TokenLedgerApi` keeps its name (it meters/credits — and is fence-restricted by D18). One port per *concern*, kept small (ISP).
- **Errors** cross the boundary as the shared `common.error.ApiException(ErrorCode …)` — `NOT_FOUND`, `OUT_OF_SCOPE`, `CONFLICT_OF_INTEREST` — so the caller never invents a second error vocabulary (DRY; ADR-0008 envelope).

**Allowed direction (must stay acyclic — ARCHITECTURE §3.2):**

| Caller (feature) | → Callee (foundation/sibling) | Published port (to add) | Why |
|---|---|---|---|
| `reporting` | → `responders` | `responders.api.RoutingApi` / reporting consumes assignment via responders | route a report to a responder OWNER (D21) |
| `responders` | → `reporting` | `reporting.api.IssueCategoryQueryApi`, `reporting.api.ReportQueryApi` | validate `categoryId` on `Responder`/`RoutingRule`; validate `reportId` on assignment |
| `engagement` | → `institutions` + `geography` (via) `identity` | `institutions.api.RepresentativeQueryApi`, `identity.api.ElectoralScopeApi` | electoral scope on petition-sign (D13) |
| `accountability` | → `institutions`, `identity` | `institutions.api.RepresentativeQueryApi`, `identity.api.ElectoralScopeApi` | electoral scope + subject existence on rating (D13) |
| `moderation` | → `reporting`/`engagement`/`communications`/`institutions` (subject), `identity` (author) | `<owner>.api.SubjectAuthorQueryApi` (a thin per-owner lookup) | resolve `(subjectType, subjectId)` to existence + author for the self-action guard (D16) |
| `communications` | → owning module of a feed/notification source | that module's `*QueryApi` | resolve a referenced subject for a feed item |

> **The cycle hazard and how we avoid it.** `reporting` and `responders` genuinely need each other (reporting routes *to* responders; responders validate *against* reporting's categories/reports). A bidirectional **synchronous** dependency would be a cycle and is **forbidden** (ARCHITECTURE §3.2 rule 4). Resolve it by **splitting concerns by direction and synchronicity (see §2):** the *read* `responders → reporting` (category/report existence) is a synchronous `*QueryApi` call; the *act* `reporting → responders` (routing → create OWNER assignment) is **asynchronous via the outbox** (`ReportSubmitted`/`ReportRouted` event the responders routing worker consumes), so there is **no synchronous edge from `reporting` to `responders`** and therefore no cycle. This is the single most important constraint in this ADR.

### 2. These integrations are **event-driven** (async), not synchronous — and stay `// TODO(wiring)` until the outbox increment

The transactional outbox + bus (ADR-0008 §2) is **not yet built**. Every cross-module **write/side-effect**, and every read that does not have to happen inside the citizen's request transaction, is deferred to it. Until then these remain `// TODO(wiring)` (do **not** implement them as direct synchronous cross-module writes — that would create cycles and couple the citizen path to a sibling's availability):

- **Routing → responder assignment (D21):** `reporting` emits `ReportSubmitted`/`ReportRouted`; a responders **routing worker** consumes it, evaluates `RoutingRule`, and creates the OWNER `ResponderAssignment`. `reporting.Report.ownerResponderId` is then set by the responders side emitting `ResponderAssigned`, consumed by a reporting worker. (`ResponderAssignedEvent` already exists as a record awaiting the bus.)
- **Notifications & feed fan-out** (ack, status-change, announcement) — already designed as outbox workers (ARCHITECTURE §8).
- **SLA-clock start/breach**, **search indexing**, **analytics**, **token rewards for validated behaviour** (`TokenLedgerApi.reward` invoked by the worker that validated the behaviour, not inline on the citizen path).
- **Moderation takedown → source module hide:** moderation emits `ContentRemoved`; the owning module's worker hides the row. (The *read* direction — moderation resolving the subject/author — is synchronous, §1/§3c.)

Rule of thumb: **a read the citizen's write depends on → synchronous `*QueryApi`; a write/effect on another module's data, or anything tolerant of eventual consistency → event via the outbox.**

### 3. The exact `ModuleBoundaryTest` (ArchUnit) update — permit `api → api`, keep `domain`/`infrastructure` encapsulated

Add **one new rule** that encodes the whole pattern: any module may depend on another module's `..api..` package, but **no module may depend on another module's `..domain..` or `..infrastructure..`**. Because cross-module calls now legitimately go through `api`, the rule is phrased as "a feature module's classes must not reach into another feature module's *internals*", with `common` and same-module access unaffected. Concretely, add to `com.taarifu.architecture.ModuleBoundaryTest`:

```java
/**
 * No module may import another module's internal layers ({@code domain} / {@code infrastructure}).
 * Cross-module integration is permitted ONLY through the callee's published {@code ..api..} package
 * (ADR-0013): synchronous reads via a published {@code *QueryApi}/{@code *Api} port, async via events.
 * This is the rule ADR-0003's ModuleBoundaryTest Javadoc promised to add as feature modules landed.
 */
@Test
void modulesDoNotReachIntoAnotherModulesInternals() {
    // For each feature module, assert its classes do not depend on ANOTHER module's domain/infrastructure.
    // Same-module (..<m>.domain..) and the shared kernel (common) are unaffected; cross-module ..api.. is allowed.
    ArchRule rule = SlicesRuleDefinition.slices()
            .matching("com.taarifu.(*)..")          // slice = top-level module name
            .should().notDependOnEachOther()         // base: independent slices …
            .ignoreDependency(
                    resideInAPackage("com.taarifu.."),
                    resideInAnyPackage("com.taarifu.common..", "com.taarifu..api.."))
            .because("cross-module calls go through the published api package only (ADR-0013); "
                    + "domain/infrastructure stay encapsulated (ARCHITECTURE §3.2)");
    rule.allowEmptyShould(true).check(productionClasses);
}
```

If the slice-based form proves too coarse for the allow-list, the **equivalent explicit form** is the canonical fallback (and is the version we commit if the slice form flags false positives):

```java
@Test
void noModuleDependsOnAnotherModulesDomainOrInfrastructure() {
    ArchRule rule = noClasses()
            .that().resideInAPackage("com.taarifu.(*)..")
            .and().resideOutsideOfPackage("com.taarifu.common..")
            .should().dependOnClassesThat(
                    // another module's internals: a domain/infrastructure package whose module
                    // segment differs from the importing class's module segment.
                    new DescribedPredicate<>("reside in a DIFFERENT module's domain/infrastructure") {
                        @Override public boolean test(JavaClass target) {
                            return (target.getPackageName().contains(".domain.")
                                 || target.getPackageName().contains(".infrastructure."))
                                 && !sameModule(/* importing */, target);  // compare the com.taarifu.<m> segment
                        }
                    })
            .because("cross-module integration is via the published api package only (ADR-0013)");
    rule.check(productionClasses);
}
```

- **Keep all four existing rules unchanged.** The new rule **complements** them: `commonKernelDependsOnNoFeatureModule`, `controllersHaveNoTransactionalBoundary`, `domainPortsHaveNoVendorImports`, `entitiesStayWithinDomainModel` all still pass; the new rule adds the cross-module-internals fence. The suite must stay **GREEN**.
- **No rule change is needed to *permit* `api → api`** — the existing four never forbade it; the new rule is written to **carve `..api..` (and `common`) out of the deny set**, so adding the wiring ports does not turn the suite red.
- A follow-up tightening (separate increment, not now): assert each `*QueryApi` port returns no `@Entity` type (extends `entitiesStayWithinDomainModel` to api-package return types) and that `api` ports carry no vendor imports (mirror `domainPortsHaveNoVendorImports`).

### 4. How the three next-phase wirings must be done (concretely)

**(a) reporting ↔ responders routing (D21) — split by direction.**
- *Synchronous, `responders → reporting`:* `ResponderAdminService.assignResponder` and `RoutingRule`/`Responder` category validation call `reporting.api.IssueCategoryQueryApi.requireCategory(categoryId)` and `reporting.api.ReportQueryApi.requireExists(reportId)` (throwing `NOT_FOUND`). Replaces the `// TODO(wiring): validate reportId/category via reporting's API` markers.
- *Asynchronous, `reporting → responders`:* **do not** add a synchronous `reporting → responders` call (cycle). `reporting` emits `ReportRouted` (outbox); a responders routing worker creates the OWNER `ResponderAssignment` (the single-OWNER guard already exists), then emits `ResponderAssigned`; a reporting worker sets `Report.ownerResponderId` (the existing `// TODO(wiring): set on routing … (D21)` marker on `Report`). **Stays `// TODO(wiring)` until the outbox lands.**

**(b) electoral scope (D13) on petition-sign + rating — synchronous, via two `*QueryApi` ports.**
- Add `identity.api.ElectoralScopeApi` exposing `boolean isElectorOf(UUID userPublicId, UUID constituencyPublicId)` (resolves the caller's voter-ID-authoritative `isElectoral` `ProfileLocation` → its effective ward→constituency, §9.0). It lives in `identity` because identity owns the profile's electoral location; it may itself call `geography` synchronously for the effective `WardConstituency`.
- Add `institutions.api.RepresentativeQueryApi.constituencyOf(UUID representativeId)` returning the rep's constituency `UUID` (and existence).
- `RatingService.submit` (after the existing `isNotSelf` D16 check): resolve `constituency = representativeQueryApi.constituencyOf(subjectId)`, then `if (!electoralScopeApi.isElectorOf(rater, constituency)) throw new ApiException(OUT_OF_SCOPE);` — replacing its `// TODO(wiring)` electoral block, **before** the one-per-person DB-unique step. `PetitionSignature`/`PetitionService.sign` does the identical check against the petition's constituency scope. **The fence invariant holds: neither path injects nor reads `tokens` (D18).**

**(c) moderation subject-author resolution — synchronous, via a thin per-owner lookup.**
- The owning module of each moderatable subject publishes `<owner>.api.SubjectAuthorQueryApi.authorOf(UUID subjectId)` returning the author's `UUID` (and existence). Moderation, holding only `(subjectType, subjectId)`, dispatches on `subjectType` to the matching port (a small registry/`Map<SubjectType, SubjectAuthorQueryApi>` injected by Spring — owners register their impl).
- On a queue action, moderation resolves `author = lookup(subjectType).authorOf(subjectId)` and feeds it to the existing `@taarifuAuthz.isNotSelf(author)` guard (D16) — replacing the `// TODO(wiring): resolve a subject to a concrete record` marker. The async *takedown* (moderation → owner hide) stays event-driven (§2), `// TODO(wiring)` until the outbox.

## Consequences

- (+) **One canonical mechanism**, generalised from a precedent that already exists in the codebase (`TokenLedgerApi`) — no new concept to learn; engineers wire every `// TODO(wiring)` the same way.
- (+) **The dependency graph stays acyclic and the boundary stays enforced**: synchronous edges all point feature→foundation (or are split from their reverse by the outbox), and the new ArchUnit rule mechanically blocks any `domain`/`infrastructure` reach-through while permitting `api → api`. The suite stays GREEN.
- (+) **The citizen path stays fast and isolated** (PRD §15, D18): only the reads it truly needs are synchronous; all writes/effects ride the outbox, so a sibling's slowness/outage never rolls back a citizen's action, and the integrity fence (binding actions read tier + electoral scope + one-per-person, never tokens) is preserved by construction.
- (+) **Extract-to-service survives** (ARCHITECTURE §10): each `*QueryApi` is already the only synchronous contract, so a module can move behind a network call (the interface becomes a Feign/RPC client) with callers unchanged.
- (−) The reporting↔responders split (sync read one way, async write the other) is **more subtle than a plain method call** — engineers must resist adding a synchronous `reporting → responders` call. Mitigated by the explicit table in §1 and the ArchUnit cycle check (`slices().should().notDependOnEachOther()`).
- (−) The `SubjectAuthorQueryApi` registry adds a small indirection in moderation (dispatch by `subjectType`). Accepted — it keeps moderation free of any concrete subject-owner import (it depends only on the interface), which is exactly the boundary we want.
- (−) Most of the value (routing, fan-out, takedown, rewards) is **gated on the unbuilt outbox** and stays `// TODO(wiring)`. Accepted and deliberate: this ADR sets the contract now so the outbox increment and the synchronous-read increment can land independently without re-litigating boundaries.
- **Revisit triggers:** (a) **outbox/bus increment lands** → implement every event-driven `// TODO(wiring)` (routing OWNER assignment, fan-out, takedown, async rewards) against the contracts fixed here; (b) **a high-load module is extracted** (PRD §16) → turn its `*QueryApi` into a remote client behind the same interface; (c) **a synchronous read shows up on a hot path's p95** → move it to a cached read model or pre-join via an event-built projection (ARCHITECTURE §8), interface unchanged; (d) **a fourth integration shape appears** (e.g. a synchronous cross-feature *command* that is not metering) → reassess whether it belongs on the outbox before adding a new sync command port.

## Decision summary

- **Synchronous cross-module reads** use the callee's **published `com.taarifu.<callee>.api` query port** (`*QueryApi` / the existing `TokenLedgerApi` shape): a plain interface taking/returning only DTOs/enums/`UUID`s, impl in `application.service`, caller injects the interface — never the entity, repository, `domain`, or `infrastructure`. Allowed directions are feature→foundation per the §1 table; **reporting↔responders is deliberately split** (read `responders→reporting` synchronous; act `reporting→responders` asynchronous) so there is **no cycle**.
- **Writes/side-effects and eventual-consistency reads** are **event-driven via the transactional outbox** (ADR-0008) — routing→OWNER assignment (D21), notifications/feed fan-out, SLA clocks, search/analytics, rewards, moderation takedown. The outbox is **not yet built**, so these stay `// TODO(wiring)`.
- **ArchUnit:** add **one** rule to `ModuleBoundaryTest` — *no module depends on another module's `..domain..`/`..infrastructure..`*, with `common` and cross-module `..api..` carved out — which **permits `api → api`** while keeping internals encapsulated; the existing four rules are unchanged and the suite stays **GREEN**.
- **The three next wirings:** (a) routing = sync `responders→reporting` category/report validation + async `reporting→responders` OWNER assignment; (b) electoral scope (D13) on petition-sign + rating = sync `identity.api.ElectoralScopeApi.isElectorOf` × `institutions.api.RepresentativeQueryApi.constituencyOf`, fence-preserving (no tokens); (c) moderation = sync `<owner>.api.SubjectAuthorQueryApi.authorOf` dispatched by `subjectType` to feed the `isNotSelf` (D16) guard, takedown async.
