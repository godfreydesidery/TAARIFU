# Foundation Increment — Build Scope

> **Status:** Accepted. **Audience:** backend engineer(s) building the first increment.
> **Companion:** [ARCHITECTURE.md](ARCHITECTURE.md) (the design) and [`../adr/`](../adr/) (the decisions).
> **Grounding:** PRD §9.0 (geography), §9.1 (entities), §11 (M1 geography read), §16/§17/§18 (architecture/API/security); CLAUDE.md (rules).

This document defines **exactly** what to build in THIS increment and the **exact package layout + naming** to follow. It is deliberately narrow: a working shared kernel, **one full read-only vertical slice (GEOGRAPHY)**, and the **IDENTITY data layer only**. Auth flows, reporting, and the feature modules are the **next** increments.

---

## 1. Increment goal (Definition of Done for this slice)

A running Spring Boot app that:
1. boots with **Flyway-migrated** schema and **`ddl-auto=validate`** green;
2. serves the **GEOGRAPHY** read API end-to-end (entities → repository → service → controller → DTO) wrapped in the **single `ApiResponse` envelope**, documented in **OpenAPI**, covered by **unit + Testcontainers integration tests** (≥80% on the slice);
3. has the **IDENTITY** entities + repositories + enums in place (no controllers/services/auth yet) with their Flyway migration validating;
4. every component carries **Javadoc** (CLAUDE.md §8 — mandatory), and there are **no committed secrets**.

Out of scope this increment: any write/admin endpoints, auth/JWT/OTP, reporting, tokens, notifications, the other feature modules. Their **packages may be created empty** to lock the layout, but **no code**.

---

## 2. Build order (do them in this sequence)

1. **Project skeleton** — Maven multi-module, parent POM with pinned versions (ARCHITECTURE §2), `TaarifuApplication`, `application.yml` (`ddl-auto=validate`, env-placeholder config, **no secrets**).
2. **`common` shared kernel** (§3 below) — nothing else compiles without it.
3. **Flyway baseline** `V001__baseline.sql` — PostGIS extension, code/audit sequences, common conventions.
4. **`geography` full vertical slice** (§4 below) — the proof that the whole stack works.
5. **`identity` data layer only** (§5 below) — entities + repositories + enums + migration.
6. **ArchUnit boundary tests + slice tests** (§6) and **OpenAPI** commit.

---

## 3. (a) Shared kernel — `com.taarifu.common`

Build these, each with Javadoc stating responsibility + the "why" for non-obvious rules (CLAUDE.md §8).

```
com.taarifu.common
├── api/
│   ├── dto/
│   │   ├── ApiResponse.java          // generic envelope {success,code,message,data,meta,timestamp}
│   │   ├── PageMeta.java             // {page,size,total,totalPages}
│   │   └── ErrorDetail.java          // {field,code,message} for validation errors[]
│   └── ResponseFactory.java          // ok(), created(), paged(Page), error(...) — builds the envelope
├── domain/
│   ├── model/
│   │   ├── BaseEntity.java           // id(Long), publicId(UUID), version, audit, soft-delete (ARCHITECTURE §4.2)
│   │   └── BaseCodedEntity.java      // BaseEntity + code(String) from a DB sequence
│   └── port/
│       └── ClockPort.java            // injectable time (testability)   [CryptoPort/ObjectStorePort: stub later]
├── error/
│   ├── ErrorCode.java                // enum: code + HTTP status + i18n key (OK, NOT_FOUND, VALIDATION_FAILED, CONFLICT, …)
│   ├── ApiException.java             // base runtime exception carrying an ErrorCode + args
│   ├── ResourceNotFoundException.java
│   └── GlobalExceptionHandler.java   // @RestControllerAdvice → ApiResponse for ALL exceptions (ARCHITECTURE §5.2)
├── persistence/
│   ├── JpaAuditingConfig.java        // @EnableJpaAuditing
│   ├── AuditorAwareImpl.java         // current actor UUID (stub: system actor until auth lands)
│   └── CodeGenerator.java            // human codes from a DB sequence (e.g. TAR-YYYY-NNNNNN)
├── pagination/
│   ├── PageRequestFactory.java       // parse page/size/sort with caps (size ≤ 100, default 20)
│   └── PageMapper.java               // Spring Page<T> → ApiResponse data+meta
├── i18n/
│   └── MessageResolver.java          // resolve i18n keys, SW default + EN (ARCHITECTURE §5.1)
└── config/
    ├── OpenApiConfig.java            // springdoc: title, /api/v1 server, security scheme placeholder
    └── JacksonConfig.java            // Instant ISO-8601 UTC, snake/camel policy, null handling
```

**Rules the kernel sets for every later module:**
- DTOs at the boundary; **entities never leave a module** (CLAUDE.md §8).
- One envelope only; controllers use `ResponseFactory`, never hand-build JSON.
- All exceptions become `ApiResponse` via `GlobalExceptionHandler` — controllers don't try/catch for shape.
- Soft-delete default-filtered in repositories; optimistic `@Version` on every entity.

---

## 4. (b) GEOGRAPHY — full read-only vertical slice — `com.taarifu.geography`

> The reference slice. Build it **completely** (entities→repo→service→controller→DTO→mapper→tests→OpenAPI). Everything is **read-only** this increment (seed data loads via Flyway/admin import in a later increment; here a small `V002`/`afterMigrate` test seed or a `R__seed_geography_dev.sql` dev seed is fine). PRD §9.0, §9.1, §11 (M1).

```
com.taarifu.geography
├── api/
│   ├── controller/
│   │   └── GeographyController.java      // GET-only; /api/v1 ; @PreAuthorize("permitAll()") for public reads
│   └── dto/
│       ├── RegionDto.java   DistrictDto.java   CouncilDto.java
│       ├── WardDto.java   VillageDto.java
│       ├── ConstituencyDto.java
│       └── LocationResolutionDto.java     // result of GPS→ward + derived admin chain + constituency
├── application/
│   ├── service/
│   │   ├── GeographyQueryService.java     // hierarchy lookups, children-of, ancestors-of
│   │   └── LocationResolutionService.java // GPS→ward (PostGIS) + derive chain + WardConstituency (effective-dated)
│   └── mapper/
│       └── GeographyMapper.java           // MapStruct entity→DTO
├── domain/
│   ├── model/
│   │   ├── Location.java                  // single table, discriminated by LocationType; parent FK; optional geometry
│   │   ├── LocationClosure.java           // (ancestor_id, descendant_id, depth) closure table
│   │   ├── Constituency.java              // belongs to District
│   │   ├── WardConstituency.java          // effective-dated bridge Ward→Constituency
│   │   └── enums/
│   │       ├── LocationType.java          // REGION,DISTRICT,COUNCIL,DIVISION,WARD,VILLAGE,MTAA,HAMLET
│   │       └── LocationStatus.java
│   ├── repository/
│   │   ├── LocationRepository.java        // findByPublicId, findChildren, findByType, etc.
│   │   ├── LocationClosureRepository.java // descendants/ancestors queries
│   │   ├── ConstituencyRepository.java
│   │   └── WardConstituencyRepository.java// resolve mapping effective at date D
│   └── port/
│       └── Geocoder.java                  // GPS→ward; INTERNAL PostGIS is the source of truth (EI-7)
└── infrastructure/
    ├── adapter/
    │   ├── PostgisGeocoder.java           // point-in-polygon against seeded boundaries (primary impl)
    │   └── StubGeocoder.java              // returns a fixed ward for dev/test (degradation/demo)
    ├── persistence/
    │   └── LocationClosureQueries.java     // native/PostGIS queries if needed
    └── config/
        └── GeographyConfig.java
```

**Endpoints (read-only, public — PRD §11 M1, §22.6 public scope):**
- `GET /api/v1/regions` — list regions (paged).
- `GET /api/v1/regions/{publicId}/districts` — children.
- `GET /api/v1/locations/{publicId}` — a location + its ancestor chain.
- `GET /api/v1/locations/{publicId}/children` — children by level.
- `GET /api/v1/constituencies/{publicId}` — constituency + current wards.
- `GET /api/v1/locations/resolve?lat=&lng=` — GPS→ward + derived admin chain + constituency (uses `Geocoder` port; degrades to manual drill-down — EI-7).

**Migration:** `V002__geography_hierarchy.sql` — `location`, `location_closure`, `constituency`, `ward_constituency` (effective-dated columns + the partial-unique constraint for "one current mapping per ward"), PostGIS geometry column on `location`, indexes (parent_id, type, GIST on geometry, closure (ancestor,descendant)). **SQL comments** on every table/non-obvious column (CLAUDE.md §8).

**Why this is the reference slice:** it exercises the entire stack (PostGIS, closure-table hierarchy, effective-dated bridge, a port+adapter+stub, the envelope, pagination, OpenAPI, Testcontainers) on **read-only** data with **no auth coupling** — so the pattern is proven before write/auth complexity lands.

---

## 5. (c) IDENTITY — entities + repositories + enums ONLY — `com.taarifu.identity`

> Build the **data layer only**. **No** controllers, **no** application services, **no** auth/JWT/OTP this increment (that is the next increment). This locks the model and the migration so the auth increment is pure behaviour. PRD §6, §7, §9.1.

```
com.taarifu.identity
├── domain/
│   ├── model/
│   │   ├── User.java                 // account: phone(unique), email, passwordHash, status, trustTier, mfaEnabled, lastLoginAt
│   │   ├── Profile.java              // PERSON|ORG, names, idNo(ENCRYPTED)+idType, idHash(blind index), verification flags, demographics
│   │   ├── ProfileLocation.java      // many per profile; associationType; isPrimary(1); isElectoral(1); FK→geography Ward(publicId/internal id)
│   │   ├── Role.java                 // role catalogue (PRD §7.2)
│   │   ├── RoleAssignment.java       // scoped: areaIds[], categoryIds[], constituencyId; role lifecycle status
│   │   ├── VerificationRequest.java  // subject, type{ID,REP_CLAIM,ORG}, evidence ref, status, reviewer
│   │   ├── RefreshToken.java         // hashed token, family id, expiry, revoked (data only; rotation logic = next increment)
│   │   └── enums/
│   │       ├── UserStatus.java       // PENDING, ACTIVE, SUSPENDED, DISABLED
│   │       ├── TrustTier.java        // T0, T1, T2, T3
│   │       ├── ProfileType.java      // PERSON, ORGANIZATION
│   │       ├── IdType.java           // NATIONAL, VOTER, PASSPORT, ...
│   │       ├── AssociationType.java  // HOME_ANCESTRAL, RESIDENCE, WORK, FAMILY, BUSINESS, PROPERTY, INTEREST
│   │       ├── RoleName.java         // GUEST, CITIZEN, ORG_MEMBER, ORG_ADMIN, REPRESENTATIVE, RESPONDER_AGENT, RESPONDER_ADMIN, MODERATOR, ADMIN, ROOT
│   │       ├── RoleStatus.java       // PENDING_VERIFICATION, ACTIVE, SUSPENDED, FORMER
│   │       └── VerificationStatus.java // PENDING, APPROVED, REJECTED, MORE_INFO
│   ├── repository/
│   │   ├── UserRepository.java       // findByPhone, findByPublicId, existsByPhone
│   │   ├── ProfileRepository.java    // findByIdHash (dedup, D15) — never decrypts
│   │   ├── ProfileLocationRepository.java
│   │   ├── RoleRepository.java   RoleAssignmentRepository.java
│   │   ├── VerificationRequestRepository.java
│   │   └── RefreshTokenRepository.java
│   └── port/
│       └── IdentityVerificationProvider.java   // interface only (impls = next increment)
└── (NO api/ , NO application/ , infrastructure/ may hold only enum/converter helpers this increment)
```

**Hard rules for this slice (PRD §18, §9.0, D11/D12/D15):**
- `User.phone` **unique** (one account per phone, D11/D15).
- `Profile.idNo` stored via the **`CryptoPort` AttributeConverter (field-level encryption)**; a separate **`idHash`** (blind index over `idType+idNo`) column is **unique** and drives dedup (D15) **without decrypting** (PRD §18, EI-1). *(CryptoPort may be a dev-key stub this increment; the column shapes are what matter.)*
- `ProfileLocation`: DB constraints enforce **at most one `isPrimary=true`** and **at most one `isElectoral=true`** per profile (partial unique index). `isElectoral` change-cooldown logic is the next increment; the columns exist now.
- `ProfileLocation` is **private PII** — no public DTO is built this increment by design (PRD §9.0).
- **Real FKs** to geography (`ProfileLocation` → `Location`/Ward) — no loose `Long` ids (fixes legacy, PRD §6.3).

**Migration:** `V101__identity_core.sql` — `app_user`, `profile`, `profile_location`, `role`, `role_assignment`, `verification_request`, `refresh_token`, with the unique phone, unique `id_hash`, the two partial-unique `isPrimary`/`isElectoral` indexes, FKs to `location`, and **SQL comments** (CLAUDE.md §8). Seed the `role` rows (PRD §7.2) in `V102__identity_roles_seed.sql`.

---

## 6. Tests, OpenAPI & gates (CLAUDE.md §9, §10)

- **`common`:** unit tests for `ResponseFactory`, `GlobalExceptionHandler` (each ErrorCode → status + envelope), `PageMapper`, `CodeGenerator`.
- **`geography`:** unit tests for services/mappers; **Testcontainers (PostgreSQL+PostGIS)** integration tests for repositories, closure queries, effective-dated `WardConstituency` resolution, and the `GET` controllers (envelope + pagination + OpenAPI conformance via REST Assured). **≥80% on the slice.**
- **`identity`:** Testcontainers repository tests for the unique constraints (phone, `idHash` dedup, the partial-unique primary/electoral indexes) and `ddl-auto=validate` green against `V101/V102`.
- **ArchUnit** (in `common` test base): assert the boundary rules in ARCHITECTURE §3.4 (no cross-module `domain`/`infrastructure` imports; controllers have no `@Transactional`; no entity leaks past `api`; `domain.port` has no vendor imports).
- **OpenAPI** spec committed to `/docs/api/openapi.json`; geography endpoints contract-checked.
- **CI gates:** build + tests + SAST + container scan + **migration validation** all green; **no committed secrets**.

---

## 7. Exact naming the engineer MUST follow

| Concern | Convention | Example |
|---|---|---|
| Base package | `com.taarifu.<module>` | `com.taarifu.geography` |
| Internal layering | `api` / `application` / `domain` / `infrastructure` | (ARCHITECTURE §3.3) |
| Public sub-packages | `api.controller`, `api.dto`, `api.event` | `geography.api.dto.RegionDto` |
| Entities | `PascalCase`, extend `BaseEntity`/`BaseCodedEntity` | `WardConstituency` |
| Tables / columns | `snake_case` | `ward_constituency`, `effective_from` |
| Enums | `PascalCase` type, `UPPER_SNAKE` values | `LocationType.REGION` |
| Repositories | `<Entity>Repository` (Spring Data) | `LocationRepository` |
| Services | `<Concern>Service` (application layer, `@Transactional`) | `GeographyQueryService` |
| Controllers | `<Module>Controller` or `<Resource>Controller`, thin | `GeographyController` |
| DTOs | `<Resource>Dto` (request: `<Action><Resource>Request`) | `RegionDto` |
| Ports | intention-revealing interface in `domain.port` | `Geocoder` |
| Adapters | `<Vendor/Tech><Port>` + a `Stub<Port>` in `infrastructure.adapter` | `PostgisGeocoder`, `StubGeocoder` |
| Migrations | `V<NNN>__<module>_<change>.sql`, range-partitioned (ARCHITECTURE §4.1) | `V002__geography_hierarchy.sql` |
| Endpoints | `/api/v1/<plural-noun>`, public **UUID** ids | `/api/v1/regions/{publicId}` |
| i18n keys | `<module>.<concept>.<detail>` | `geography.region.notFound` |
| Docs | **Javadoc on every** class/public method/endpoint/non-obvious field (CLAUDE.md §8) | — |

> Anything not covered here defers to [ARCHITECTURE.md](ARCHITECTURE.md). A deviation needs an ADR (CLAUDE.md §2, design-first).
