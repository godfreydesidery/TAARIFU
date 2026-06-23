# ADR-0006: UUID public ids + internal numeric PKs + DB-sequence human codes

**Status:** Accepted · 2026-06-23 · Solution Architect
**Grounding:** PRD §17 (public ids = UUID/ULID; human codes), §9 (BaseEntity), §6.3 (fix the `id+uid+code` triple); CLAUDE.md §8.

## Context
Sequential database ids in URLs are **enumerable** (an attacker walks `/reports/1,2,3…`) and leak volume/order — a security and privacy problem (PRD §17). Users still need **human-readable codes** (e.g. a ticket number to quote on the phone). The legacy schema carried a confusing parallel **`id + uid + code`** triple on entities and used **loose `Long` foreign id fields without FKs** (PRD §6.3). We need one disciplined, DRY pattern.

## Decision
A single **`BaseEntity`** in the shared kernel gives every entity:
- `id` — **internal** `Long` surrogate PK, used for **real FKs** and joins, **never exposed** in APIs;
- `publicId` — **UUID** (time-ordered v7/ULID for index locality), unique, the **only id in URLs/DTOs** (PRD §17);
- audit (`createdAt/By`, `updatedAt/By`), soft-delete (`deleted/At/By`), optimistic `@Version`.

**Human-readable codes are a separate, opt-in concern** (`BaseCodedEntity`): only entities users see by code (e.g. `Report` ticket `TAR-YYYY-NNNNNN`) carry a `code` populated from a **DB sequence** via `common.persistence.CodeGenerator`. So: UUID = public/machine id, code = human display id, internal `Long` = FK/join key.

## Consequences
- (+) URLs are non-enumerable; FKs are real and indexed; one clear pattern replaces the legacy triple (DRY).
- (+) Time-ordered UUIDs avoid random-UUID index fragmentation at scale.
- (−) Every public lookup is "by `publicId`" (an extra indexed column + unique constraint) rather than by PK — negligible cost, accepted.
- (−) Code sequences are per-entity-type and must be created in migrations — accepted; documented in ARCHITECTURE §4.2.
- **Revisit trigger:** none expected; if a code format must change, it is a new sequence/format, never a rewrite of issued codes (history is stable).
