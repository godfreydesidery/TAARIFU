# ADR-0017: Cross-entity search & discovery — a `search` module that owns a Postgres full-text read-projection (`search_document`), populated by the owning modules pushing their own projections through a published inbound `SearchIndexApi` port (+ outbox-driven visibility maintenance), and queried with `tsvector`/GIN ranking that respects each entity's visibility server-side

**Status:** Accepted · 2026-06-25 · Backend (Baraka Mushi), grounded in Solution-Architect decisions ADR-0013/ADR-0014
**Extends:** ADR-0002 (modular monolith), ADR-0003 (package-by-feature + `ModuleBoundaryTest`), ADR-0004 (ports & adapters), ADR-0008 (single envelope + transactional outbox), **ADR-0013 (cross-module integration: published `api` ports for sync, events for async; no `domain`/`infrastructure` reach-in)**, **ADR-0014 (the transactional outbox/in-process bus this ADR reuses)**.
**Grounding:** PRD discovery requirement (citizens + staff finding representatives, organisations/responders, announcements, issue-categories, public reports by name/keyword + area/category filters); PRD §17 (single envelope, pagination, UUID ids); §18 (visibility/PII — private/sensitive only to authorised staff; PII never in logs/events); §15 (lean payloads, p95); §9.0 (area filtering at Ward-or-coarser grain); ARCHITECTURE.md §3 (module map/boundaries), §7 (`SearchPort`: "Postgres FTS (MVP), OpenSearch (later); stub"), §8 (outbox), §10 (extract-to-service). CLAUDE.md §3 (KISS/DRY/clean boundaries), §8 (no entity leak, UUID ids, full doc-comments), §12 (no PII leak, no bypass of method authz).

## Context

The platform now has the entities a citizen or staff member needs to *discover*: representatives (`institutions`), organisations/responders (`responders`), announcements (`communications`), issue-categories + public reports (`reporting`). What is missing is a **single cross-entity search** — "type `maji` (water) and find the water-issue category, the water-responder org, the ward councillor, and the recent public water reports", filterable by area (Kata-or-coarser) and category. Three forces shape the design:

1. **Boundary rule (ADR-0013, ARCHITECTURE §3.2).** A search must read text owned by five other modules. It may **not** reach into their `domain`/`infrastructure` (no cross-module FK join, no foreign repository, no foreign entity). The only sanctioned cross-module contracts are (a) a callee's published `..api..` port and (b) outbox events. Search is also a designated **extract-to-service** module (ARCHITECTURE §10), so its contract must survive becoming a network hop.
2. **The searchable text is PII-discipline-free public data, but the existing async events carry no text.** `AnnouncementPublished`/`ReportRouted` carry **ids/codes only — never titles/bodies** (ADR-0014 §1, PRD §18). So an outbox handler alone *cannot* build a text index from today's events; it would have to call back synchronously into producers, and no producer exposes a rich "give me your searchable projection" read port. Forcing one onto every module (and a sync edge from `search` into five siblings) is the wrong shape.
3. **Visibility is per-entity and must be enforced server-side (PRD §18).** A guest sees only public rows (published announcements, public reports, the public directory). Private/sensitive rows (e.g. sensitive/anonymous reports, unpublished content) are visible **only to authorised staff under the same scope rules** — never leaked through a search result, never distinguishable by 403-vs-empty (anti-enumeration).

## Decision

Build a **new `com.taarifu.search` feature module** that owns **one denormalised, full-text read-projection table `search_document`** (Postgres `tsvector` + GIN, migration **V146**), is **populated by the owning modules pushing their own projection** through a **published inbound port `com.taarifu.search.api.SearchIndexApi`** (the "index-on-write" / CQRS read-model pattern), and is **queried** through `GET /search` with `ts_rank` ranking, area/category filters, and **server-side visibility gating**. The outbox is reused for **visibility maintenance** (account suspension → hide that author's documents), not as the primary text source.

This is the KISS realisation of ARCHITECTURE §7's `SearchPort` ("Postgres FTS (MVP), OpenSearch (later)"): the `search_document` table **is** the search index; swapping to OpenSearch later replaces this module's persistence behind the same `SearchIndexApi` + `GET /search` contracts.

### 1. Population: owning modules push their own projection through `SearchIndexApi` (inbound port) — NOT search reaching in

`com.taarifu.search.api.SearchIndexApi` is a plain interface **implemented inside `search`** and **called by each owning module** when it creates/updates/removes a searchable thing. The owning module passes **only its own public data** (`SearchDocumentUpsert`: entity type, its public id, title, an SW/EN snippet, free keywords, optional area/category public ids, a `visibility`, and the authoring account's public id). Direction is **owner → search** (the same direction as `tokens.api.TokenLedgerApi` callers, ADR-0013 §1), so there is **no `search → sibling` edge and no cycle**: search depends on nobody; everybody may depend on search's `api`.

- **WHY push, not pull:** the searchable text lives in the owner; the owner already has it on its write path; pushing a flat projection keeps search ignorant of every source's schema (DRY — one index shape, not five join queries) and keeps the boundary clean (search never imports a foreign `domain`). It is the standard search-index/CQRS read-model pattern and exactly what an extracted OpenSearch service would receive.
- **No PII in the projection** (PRD §18): the projection carries public display fields only (a representative's name and seat, an org's name, an announcement/category/report title + public snippet). It never carries phone/national-ID/voter-ID/free GPS; `authoredByAccountId` is an opaque UUID used solely for visibility maintenance (§3), never returned to a non-owner.
- **Isolation note (this increment):** per the parallel-build isolation rule, this increment creates files **only under `com.taarifu.search`**. The **producer calls** (each owning module invoking `SearchIndexApi.upsert(...)` on its write path) are a **CENTRAL/cross-module wiring need** owned by those modules; they are listed as `// TODO(wiring)` in this module's `package-info` and in the PR's CENTRAL NEEDS. Until they are wired, the index is correct-but-empty (a healthy cold-start state — discovery degrades to "no results", never to a leak).

### 2. `search_document` — one FTS read-projection table (migration V146, search's reserved block V146–V149)

`com.taarifu.search.domain.model.SearchDocument extends BaseEntity` (id + public_id + audit + soft-delete + version — ARCHITECTURE §4.2). Key columns:

| Column | Type | Purpose / WHY |
|---|---|---|
| `entity_type` | `varchar(32)` | which kind of thing (`REPRESENTATIVE`/`ORGANISATION`/`ANNOUNCEMENT`/`ISSUE_CATEGORY`/`PUBLIC_REPORT`). Result grouping + per-type filtering. |
| `entity_public_id` | `uuid` | the source aggregate's **public** id (opaque cross-module ref — ADR-0013; **never a FK**). The client re-reads the full record from the owning module by this id. |
| `title` | `varchar(512)` | the primary display label (rep name+seat / org name / announcement or category or report title). |
| `snippet_sw` / `snippet_en` | `varchar(1024)` | a short localised, **public** preview — lean payload (PRD §15). |
| `keywords` | `varchar(1024)` | extra searchable terms the owner supplies (Swahili synonyms, codes). |
| `area_id` | `uuid` null | Ward-or-coarser area public id for the area filter (PRD §9.0); bare UUID, not a FK. |
| `category_id` | `uuid` null | issue-category public id for the category filter; bare UUID, not a FK. |
| `visibility` | `varchar(16)` | `PUBLIC` (any reader) or `STAFF` (authorised staff only) — the server-side gate (§4). |
| `authored_by_account_id` | `uuid` null | opaque author **account** id, used only for visibility maintenance (§3) and never returned. |
| `search_vector` | `tsvector` (GENERATED) | the FTS document: weighted `title` (A) + `keywords` (B) + snippets (C). |

- **The `tsvector` is a Postgres `GENERATED ALWAYS … STORED` column** over `title`/`keywords`/`snippet_*` using the `simple` configuration (Swahili is not a built-in Postgres FTS dictionary; `simple` does case-fold + tokenisation without English stemming, which is the safe choice for a Swahili-first corpus — English stemming would mangle Swahili tokens). WHY generated-stored not a trigger: it is declarative, cannot drift, and `ddl-auto=validate` tolerates it (the entity maps `search_vector` as non-insertable/updatable). The column is real DDL the entity declares `insertable=false, updatable=false`.
- **GIN index** `ix_search_document_vector ON search_document USING GIN (search_vector)` — the FTS query path.
- **Filter b-tree indexes** on `(entity_type)`, `(area_id)`, `(category_id)`, `(visibility)`, and a **unique** `(entity_type, entity_public_id)` among live rows (partial `WHERE deleted = false`) so a re-`upsert` of the same source updates one row, never duplicates (idempotent push — §1/§3).

### 3. Outbox handler: visibility maintenance only (`MODERATION_SANCTION_APPLIED`) — reuse ADR-0014, no new text path

Search registers one `DomainEventHandler` (ADR-0014 §4) on the **existing** `MODERATION_SANCTION_APPLIED` event (it carries the sanctioned **account** public id + `SanctionType` — ids/enums only). On a `SUSPEND`, the handler **hides every `search_document` authored by that account** (sets `visibility = STAFF`, idempotently — re-applying to an already-hidden set is a no-op). WHY: a suspended author's content must drop out of public discovery immediately, without search reaching into moderation/identity (the event is the sanctioned async contract — ADR-0013 §2/§3). This proves the outbox seam for search and honours the takedown rule; the broader per-document `ContentRemoved` takedown is a documented follow-up (it does not exist as an event yet). Handler is **idempotent** by construction (an UPDATE to a target state) and reads **no PII** from the payload.

### 4. Query: `GET /search`, ranked FTS, filtered, visibility-gated server-side

`SearchController` exposes `GET /search?q=&type=&areaId=&categoryId=&page=&size=` returning the single `ApiResponse` paged envelope of `SearchResultDto` (entity type, public id, title, localised snippet, area/category ids, rank). `SearchQueryService` runs a **native** query: `WHERE search_vector @@ websearch_to_tsquery('simple', :q)`, ordered by `ts_rank(search_vector, …) DESC`, with `entity_type`/`area_id`/`category_id` filters and a **mandatory visibility predicate**.

- **Authorization (method-aware, server-side):** `@PreAuthorize("permitAll()")` so guests can search the public civic graph (like `/representatives/**`, `/announcements/*`) — but the **`STAFF` visibility tier is added to the predicate only when the caller actually holds a staff role** (`MODERATOR`/`ADMIN`/`ROOT`, resolved from `CurrentUser`); a guest or ordinary citizen sees `visibility = PUBLIC` rows only. Private/sensitive rows are therefore **filtered out of the result set**, never 403'd individually (anti-enumeration — PRD §18). The `/search/**` GET pattern needs central allow-listing in `SecurityConfig.PUBLIC_GET_PATTERNS` (a CENTRAL NEED — search must not edit `common.security`).
- **WHY `websearch_to_tsquery`:** it parses lay user input (quoted phrases, `or`, `-term`) safely without throwing on malformed syntax — robust for a feature-phone/low-literacy audience typing free text (PRD §14/§15). The `simple` config matches the generated column's config (a query config must match the index config to use the GIN index).
- **Lean + paged** (PRD §15/§17): bounded page size via `PageRequestFactory`; only display fields returned; the client re-reads full detail from the owning module by `entityPublicId` when a result is tapped.

## Consequences

- (+) **Boundaries hold and stay acyclic:** search depends on `common` only; siblings depend on `search.api` (owner→search push), exactly the ADR-0013 direction. No `search → sibling` sync edge, no cross-module FK/join, no foreign `domain` import — `ModuleBoundaryTest` stays GREEN.
- (+) **No PII in the index, events, or logs** (PRD §18): the pushed projection is public display fields + opaque ids; the consumed event carries ids/enums only; the service logs counts + the query term length, never the corpus.
- (+) **KISS / no new infra** (ARCHITECTURE §7): Postgres FTS the platform already runs; one table, one GIN index, one query. The `SearchIndexApi` + `GET /search` contracts are exactly what an OpenSearch extraction would reuse (ARCHITECTURE §10) — the documented revisit trigger.
- (+) **Visibility is server-side and enforced by construction:** the predicate, not the row, decides; private rows are unrepresentable in a guest's result.
- (−) **Eventual freshness / a real wiring debt:** the index reflects the corpus only once owning modules call `SearchIndexApi.upsert(...)` on their write paths — a cross-module wiring step this increment cannot make (isolation). Until wired, results are empty (safe). Mitigated: the contract + table + query land now so each owner wires one call in its own increment; an optional one-off backfill job per owner is a follow-up.
- (−) **`simple` FTS config = no Swahili stemming/synonyms:** `maji`/`majini` won't match by stem. Accepted for MVP (owner-supplied `keywords` carry synonyms); a Swahili dictionary/`unaccent` + synonym thesaurus is a documented follow-up.
- (−) **Account-suspension hides at author granularity, not per-item takedown:** the only existing event is account-scoped. A per-document `ContentRemoved` takedown is a follow-up when that event exists.
- **Revisit triggers:** (a) FTS outgrows Postgres / needs ranking features → move `SearchIndexApi` + `GET /search` behind an OpenSearch service (ARCHITECTURE §10), contracts unchanged; (b) `ContentRemoved` event lands → add a per-document hide handler; (c) Swahili relevance complaints → add `unaccent` + a synonym dictionary config and regenerate the column.

## Decision summary

- **New `com.taarifu.search` module** owning **`search_document`** (migration **V146**, reserved block V146–V149): a denormalised FTS read-projection — `entity_type`, `entity_public_id (uuid, not a FK)`, `title`, `snippet_sw/en`, `keywords`, `area_id`, `category_id`, `visibility (PUBLIC|STAFF)`, `authored_by_account_id`, a **GENERATED `tsvector`** + **GIN** index, and a live-scoped unique `(entity_type, entity_public_id)`. `ddl-auto=validate`.
- **Population = owners push** their own public projection via the published inbound **`search.api.SearchIndexApi`** (`upsert`/`remove`) — owner→search direction, no cycle, no reach-in, no PII. Producer calls are a **CENTRAL/cross-module wiring need** (`// TODO(wiring)`), not made in this isolated increment.
- **Outbox handler** (reusing ADR-0014) on **`MODERATION_SANCTION_APPLIED`** hides a suspended author's documents (idempotent, ids-only) — visibility maintenance, the proof the outbox seam works for search.
- **Query** `GET /search` (`permitAll`, paged single envelope): native `websearch_to_tsquery('simple', q)` `@@ search_vector`, `ts_rank` ordering, `type`/`area`/`category` filters, and a **server-side visibility predicate** (`STAFF` rows only for staff roles; guests/citizens see `PUBLIC` only — anti-enumeration). `/search/**` GET needs central allow-listing (CENTRAL NEED).
