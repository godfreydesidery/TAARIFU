# ADR-0016: PDPA consent + data-subject-rights (DSR) — a new `com.taarifu.privacy` module for versioned consent records and ACCESS (export) / ERASURE (right-to-be-forgotten) requests, with the IDENTITY erasure handler that crypto-shreds the encrypted national/voter ID and tombstones the person while keeping the append-only audit hash-chain and the de-identified civic record intact

**Status:** Accepted · 2026-06-25 · Security & Privacy Engineer (Salim Juma)
**Extends:** ADR-0008 (single envelope + transactional outbox), ADR-0011 (authentication & audit — the append-only `audit_event` hash-chain, `IDENTITY_ERASED` tombstone type), ADR-0012 (identity verification & tier — `Profile.idNo` field-encrypted + `idHash` blind index), ADR-0013 (cross-module integration — `*QueryApi` synchronous reads, events for async), ADR-0014 (transactional outbox + in-process bus — `OutboxWriter`/`DomainEventHandler` SPI). This ADR adds the **launch-gate PDPA capability** the others assumed: a citizen-facing consent ledger and the access/erasure rights machinery that reconciles erasure with the immutable audit log and one-account-permanence.
**Grounding:** PRD §18 (PDPA 2022/2023; PII encryption; consent + privacy center; right to access/erasure), §25.1 (data lifecycle, retention & erasure — *erasure = anonymisation, not deletion of civic record*; audit stays immutable, stores references/hashes; tombstone `anonymized_user_#`; legal hold; SLA ack ≤72h / complete ≤30 days), §6.4 / D15 (one person = one account; account/identity permanence), §22.1 (`ProfileLocation` is private PII), §26 R12 (PDPA non-compliance risk), §28 (analytics: salted `actor_ref`, separate key for erasure). UC-A16 (manage privacy/consent), UC-A17 (deactivate/delete account = right to erasure), UC-S09 (data-export/erasure job). ARCHITECTURE.md §3 (module map/boundaries), §6.2 (deny-by-default method security), §8 (outbox). CLAUDE.md §3 (secure & private by default), §8 (no PII in logs), §12 (guardrails).

## Context

The MVP shipped with the PII controls (field-level encryption of `Profile.idNo`, blind-index dedup, the append-only `audit_event` hash-chain, pseudonymised analytics) but **without the data-subject-rights surface PDPA 2022/2023 obligates**: there is no consent ledger, no way for a citizen to request an export of their data, and no erasure machinery. PRD §18 lists these as launch-gate items and the launch checklist (§"Legal/privacy sign-off") blocks release on "consent & right-to-erasure (UC-A17/UC-S09) working". R12 (PDPA non-compliance) is rated M/H.

Three hard constraints shape the erasure design — they are the tensions PRD §25.1 was written to resolve:

1. **Right-to-erasure vs. the immutable audit log.** The `audit_event` table is append-only with a tamper-evident hash-chain (ADR-0011); a single edited or deleted row breaks the chain and destroys the compliance evidence. Erasure therefore **cannot** mutate or delete audit rows. PRD §25.1: *"Audit log stays immutable but stores references/hashes, not raw PII; erasure writes a new tombstone event rather than mutating history."* The `IDENTITY_ERASED` audit type already exists for exactly this.
2. **Right-to-erasure vs. account/identity permanence (D15, §6.4).** One person = one account, enforced by the unique `idHash` blind index. Hard-deleting the account row would (a) orphan or cascade-break the de-identified civic record (reports, signature counts, ratings aggregates) PRD §25.1 says must persist, and (b) free the phone/identity for a *second* account, defeating one-account-per-person. Erasure is therefore **de-identification + tombstoning**, not row deletion.
3. **Cross-module reach.** A citizen's PII and civic footprint is spread across `identity` (the person), `reporting` (reporter), `engagement` (signatures), `accountability` (ratings), `communications` (notifications/subscriptions), `media` (attachments/EXIF), `tokens` (wallet), `analytics` (the `actor_ref` mapping). The privacy module must **not** reach into any of those modules' `domain`/`infrastructure` (ARCHITECTURE §3.2) — it aggregates exports via published read ports and drives erasure via an event each owner reacts to on its own data.

This increment builds the **privacy module + the IDENTITY erasure handler** (the highest-sensitivity severing — the encrypted national/voter ID). Every other module's export contributor and erasure handler is enumerated as a **CENTRAL INTEGRATION NEED** and built as its own follow-up increment against the contracts fixed here; until an owner ships its handler, that module's data is simply not yet covered by the asynchronous fan-out (the ERASURE_REQUESTED event is published and waits for handlers — additive, ADR-0014 §4 "an event with no registered handler is dispatched to zero handlers and marked PROCESSED").

## Decision

Build a new foundation module **`com.taarifu.privacy`** (four-layer, depends only on `common` + foundation `*Api` read ports) with: a **versioned consent ledger** (`Consent`), a **data-subject-request intake** (`DataSubjectRequest` for `ACCESS`/`ERASURE`), a **synchronous export aggregator** that pulls the subject's data through other modules' published `api` read ports, and an **erasure trigger** that publishes an `ERASURE_REQUESTED` outbox event. Implement the **IDENTITY erasure handler** (in `identity`, the owner of the person) that crypto-shreds the encrypted `idNo`, nulls the PII, deletes `ProfileLocation`s, tombstones the account, and writes the `IDENTITY_ERASED` audit tombstone — **never touching the audit hash-chain**. Method-secured (subject self-service + ADMIN/ROOT oversight); fully audited. Flyway block **V140–V145** (a new reserved privacy range).

### 1. Module placement & boundaries

`privacy` is a **foundation module** (ARCHITECTURE §3.2): it depends on `common` and may call other modules' published `*Api` read ports synchronously; nothing depends on `privacy` synchronously, and `privacy` publishes events. The export aggregator calls **only** published `api` ports (`identity.api.*`, future `reporting.api.SubjectDataExportApi`, …) — never a sibling `domain`/`repository`. This keeps the `ModuleBoundaryTest` GREEN by construction (the new ArchUnit rule already forbids cross-module `domain`/`infrastructure` and permits `api → api`).

Package layout:
```
com.taarifu.privacy
├── api/
│   ├── controller   ConsentController, DataSubjectRequestController
│   ├── dto          ConsentDto, RecordConsentRequest, DsrDto, SubjectDataExport, ...
│   └── event        ErasureRequested (the outbox payload — ids/codes only, NO PII)
├── application/
│   └── service      ConsentService, DataSubjectRequestService, SubjectDataExportService,
│                    SubjectExportContributorRegistry
├── domain/
│   ├── model        Consent, DataSubjectRequest + enums (ConsentPurpose, ConsentState,
│   │                DsrType, DsrStatus)
│   └── repository   ConsentRepository, DataSubjectRequestRepository
└── (no infrastructure adapter — no external service; the export pulls in-process api ports)
```

### 2. Consent ledger — `Consent` (versioned, append-on-change)

A consent record is the lawful-basis evidence PDPA requires (PRD §18, UC-A16, US-0.7 `consent_state`). Design:

- **One row per (subject, purpose) decision, append-on-change** — a withdrawal does not edit the grant; it supersedes it. The **current** state for a purpose is the latest non-superseded row. WHY append-only: consent history is itself evidence (when did they grant, when withdraw) and must be reconstructable; it mirrors the audit philosophy (§25.1).
- **`ConsentPurpose`** enum (extensible, append-only): `DATA_SHARING_PRIVATE_RESPONDER` (the §24 private-company report sharing that PDPA gates on consent), `BEHAVIOURAL_ANALYTICS` (§28 — opted-out users still emit minimum operational events), `MARKETING_NOTIFICATIONS`, `PROFILING`. Each carries a `policyVersion` so a policy change can re-prompt.
- **`ConsentState`**: `GRANTED` | `WITHDRAWN`.
- Columns: `subject_public_id` (the account UUID — references identity by opaque id, never an FK across the boundary), `purpose`, `state`, `policy_version`, `granted_at`/`withdrawn_at`, `source` (web/app/ussd), `superseded` (boolean — the historical-row marker). **No PII** beyond the subject's own public id.
- Reads are consent-purpose-aware: `ConsentService.hasActiveConsent(subject, purpose)` is the single truth other modules will call (via a published `privacy.api.ConsentQueryApi` in a follow-up — listed as a CENTRAL NEED; this increment ships the entity + service + self-service endpoints).

### 3. DSR intake — `DataSubjectRequest` (ACCESS + ERASURE), the §25.1 SLA, legal hold

- **`DsrType`**: `ACCESS` (export) | `ERASURE` (right-to-be-forgotten).
- **`DsrStatus`** lifecycle: `RECEIVED` → `ACKNOWLEDGED` (≤72h, PRD §25.1) → `IN_PROGRESS` → `COMPLETED` | `REJECTED` | `ON_HOLD` (legal hold suspends erasure — §25.1). The completion SLA is ≤30 days; the `due_at` column carries the computed deadline for the operator dashboard.
- Columns: `subject_public_id`, `type`, `status`, `requested_at`, `acknowledged_at`, `completed_at`, `due_at`, `legal_hold` (boolean — set by ADMIN/ROOT; blocks the erasure handler), `reason_code` (machine, never PII). **No request payload PII** — the subject is identified by their authenticated account public id, not by a re-submitted name/ID.
- WHY a persisted request (not a fire-and-forget): PDPA obligates the controller to **demonstrate** it honoured the right within SLA; the row is the auditable proof, and erasure is asynchronous (it fans out across modules) so the request tracks completion.

### 4. ACCESS / export — synchronous aggregation via published read ports (no domain reach-in)

`SubjectDataExportService.export(subjectPublicId)` assembles a `SubjectDataExport` DTO by calling, **synchronously and read-only**, each owning module's published export port. To avoid coupling `privacy` to every module, owners register a **`SubjectExportContributor`** SPI (the same registry shape ADR-0013 §4c uses for `SubjectAuthorQueryApi`): a small interface `SubjectExportContributor { String section(); Object contribute(UUID subjectPublicId); }` that each module implements in **its own** `application.service` and exposes as a Spring bean; the privacy `SubjectExportContributorRegistry` injects `List<SubjectExportContributor>` and composes the export. Privacy ships the **identity** contributor (the account/profile summary — masked, decrypted-ID **excluded**: an export gives the subject *their* data, and the ID number is returned only if law requires it; the default export returns ID *type + verification state*, not the number, to keep the export itself from becoming a fresh PII copy in transit — Legal to confirm the exact field set, recorded as a CENTRAL NEED) and the **consent ledger** contributor. Every other module's contributor = CENTRAL NEED.

The export is **self-service** (the subject exports their own data — the controller binds `subjectPublicId = CurrentUser.requirePublicId()`, never a body id) or **ADMIN/ROOT** on a tracked DSR. The act of exporting is audited (a new `PRIVACY_DSR_EXPORTED` audit type).

### 5. ERASURE — publish `ERASURE_REQUESTED`; the IDENTITY handler crypto-shreds + tombstones (this increment)

Erasure is **asynchronous and idempotent**, because it severs PII across many modules and must not block the citizen nor partially-fail the request:

- `DataSubjectRequestService.requestErasure(subject)` (after the active-role constraint check, §6) creates the `ERASURE` DSR and, **in the same transaction**, `outboxWriter.append(EventEnvelope.of("ERASURE_REQUESTED", "DATA_SUBJECT", dsrPublicId, new ErasureRequested(subjectPublicId, dsrPublicId), now))`. The payload is **ids only — NO PII** (ADR-0014 §1): the subject and DSR public ids. Each owning module's erasure handler re-reads its own data by `subjectPublicId` and severs it.
- **`ErasureRequested`** is a new record in `privacy.api.event` (the cross-module contract; `EVENT_TYPE = "ERASURE_REQUESTED"`, `AGGREGATE_TYPE = "DATA_SUBJECT"`).

**The IDENTITY erasure handler (`identity.application.service.IdentityErasureHandler`, built now):**
1. Resolve the account by `subjectPublicId`; **idempotent** — if the profile is already tombstoned (`idNo == null && idHash == null && firstName == TOMBSTONE`), no-op and return (at-least-once redelivery is safe, ADR-0014 §3).
2. **Crypto-shred the national/voter ID:** null the encrypted `idNo` column **and** the `idHash` blind index. WHY this is the strongest severing available: the column held ciphertext; nulling it (and the deterministic hash) removes both the value and the dedup linkage. *(The envelope key cannot be rotated per-subject in the dev adapter; nulling the ciphertext + hash is the per-row crypto-shred. A future KMS adapter may also retire the data key — a CENTRAL NEED for SRE/KMS.)*
3. **Null the PII** on `Profile`: `firstName`/`lastName` → the tombstone label (`anonymized_user_<short>`), `dateOfBirth`/`gender`/`nationality` → null, `idType` → null, verification flags reset. On `User`: phone → a non-reusable tombstone token (preserving the unique index without freeing a real number for re-signup — one-account permanence, D15), email/handle/`passwordHash`/`mfaTotpSecret`/`mfaPendingSecret` → null, status → `DISABLED`.
4. **Delete `ProfileLocation`s** (PRD §25.1 table: `ProfileLocation` → *Deleted* on erasure — they are private PII with no civic-count value).
5. **Write the `IDENTITY_ERASED` audit tombstone** (actor = the erasing principal or SYSTEM, subject = the account) — a **new append-only row**, never a mutation; the hash-chain extends, it is never broken (§25.1, ADR-0011). The audit row carries references only.
6. The de-identified **civic record is untouched** by this handler — reports/signatures/ratings keep their rows (other modules' handlers de-identify the *reporter/author* reference, preserving counts — CENTRAL NEED).

WHY the handler lives in `identity`, not `privacy`: `identity` is the **single writer** of `app_user`/`profile` (ARCHITECTURE §3.2; the `UserAdminApi` precedent). `privacy` must not mutate another module's aggregate — it publishes the event; the owner severs its own data. This is the same producer/owner split as ADR-0013 §4.

### 6. Active-role erasure constraint (PRD §"Active-role erasure constraint", note ᵇ)

A holder of an **active staff/representative role cannot self-erase** while the role is active — the role must first be revoked/transitioned (Rep→FORMER) by an Admin (preserving civic history) before PII is severed. `DataSubjectRequestService.requestErasure` checks live role assignments via `identity.api` (a `hasActiveStaffOrRepRole(subject)` read — CENTRAL NEED to add to an identity port; until then the check is enforced by the existing role-read the service injects) and throws `CONFLICT` (`privacy.dsr.activeRoleBlocksErasure`) if so. This prevents a moderator/MP erasing their accountability trail by self-deletion.

### 7. Method security & audit

- **Self-service:** `POST /privacy/consents`, `GET /privacy/consents`, `POST /privacy/dsr/access`, `GET /privacy/dsr/access/export`, `POST /privacy/dsr/erasure` are `@PreAuthorize("isAuthenticated()")` and bind the subject to `CurrentUser.requirePublicId()` — **never** a body-supplied id (no acting-on-others). Deny-by-default; `@EnableMethodSecurity` is already on.
- **Oversight:** `GET /privacy/dsr` (list/queue), `POST /privacy/dsr/{id}/acknowledge|hold|complete|reject` are `@PreAuthorize("hasRole('ADMIN')")` (ROOT inherits via the RoleHierarchy). An operator may set legal hold and drive the SLA workflow but the actual erasure severing is the asynchronous handlers' job.
- **Audit:** new append-only `AuditEventType`s — `PRIVACY_CONSENT_CHANGED`, `PRIVACY_DSR_RECEIVED`, `PRIVACY_DSR_EXPORTED`, `PRIVACY_ERASURE_REQUESTED` (the existing `IDENTITY_ERASED` records the identity severing). References/public-ids only — never PII (PRD §18, L-1).

### 8. Flyway block V140–V145 (new reserved privacy range)

- `V140__privacy_consent.sql` — `consent` table.
- `V141__privacy_data_subject_request.sql` — `data_subject_request` table.
- `V142__audit_event_type_privacy.sql` — extends the `audit_event.event_type` CHECK domain with the new privacy types (mirrors V96/V103/V104 pattern).
- V143–V145 reserved for follow-up (consent purpose seed; cross-module export/erasure wiring).

Both tables are `BaseEntity` subclasses (id + public_id + audit + soft-delete + version), `ddl-auto=validate`-exact, with the mandatory SQL `COMMENT`s and the no-PII discipline. Cross-module subject reference is a **bare `subject_public_id uuid`, not an FK** (ADR-0013 §3.2).

## Consequences

- (+) **The PDPA launch gate is met by construction:** consent ledger (UC-A16), self-service access export and erasure (UC-A17/UC-S09), legal hold + SLA tracking (§25.1) all exist; R12 is materially reduced.
- (+) **The three §25.1 tensions are resolved exactly as the PRD directs:** erasure = de-identify + tombstone (not delete); the audit hash-chain is **extended, never mutated** (the handler only appends `IDENTITY_ERASED`); one-account permanence survives (the phone tombstone keeps the unique index without freeing a number). Crypto-shred (null ciphertext + blind index) is the strongest per-row severing of the national/voter ID available today.
- (+) **Boundaries hold:** `privacy` reaches no sibling's internals — export via registered `api` contributors, erasure via the outbox event each owner reacts to. `ModuleBoundaryTest` stays GREEN; no cycle (privacy→identity.api synchronous for the active-role check; identity reacts to privacy's event asynchronously).
- (+) **Privacy by construction in the event/outbox:** `ErasureRequested` carries ids only; the export is the only place decrypted PII could surface and it is self-service + audited + ID-number-excluded by default.
- (−) **Most of the civic-record severing is deferred to CENTRAL NEEDS** (reporting/engagement/accountability/communications/media/tokens/analytics handlers). Accepted and deliberate: this ADR fixes the `ErasureRequested` contract + the `SubjectExportContributor` SPI so each owner ships its handler/contributor independently without re-litigating boundaries (ADR-0014 §4 — an event with no handler is a no-op, so partial coverage is safe and additive).
- (−) **Crypto-shred is row-level, not key-level** in the dev adapter — a KMS data-key retirement per subject is stronger but needs the production KMS adapter (CENTRAL NEED for SRE/KMS). Nulling ciphertext + blind index is sufficient for PDPA erasure of the value today.
- (−) The default export **omits the raw ID number** pending Legal's field-set sign-off; if Legal requires the number in a subject's own export, it is one contributor-field change. Routed to Legal rather than guessed.
- **Revisit triggers:** (a) **each owner ships its erasure handler / export contributor** (the CENTRAL NEEDS) → the civic-record de-identification completes; (b) **production KMS adapter lands** → add per-subject data-key retirement to the identity handler; (c) **`privacy.api.ConsentQueryApi` is needed** by responders (§24 private-responder sharing gate) → publish it as a synchronous read port; (d) **Legal sign-off on the export field set / residency** → adjust the identity contributor.

## Decision summary

- **New `com.taarifu.privacy` foundation module:** versioned **`Consent`** ledger (purpose × state × policy_version, append-on-change) and **`DataSubjectRequest`** intake (`ACCESS`/`ERASURE`, RECEIVED→ACKNOWLEDGED→IN_PROGRESS→COMPLETED/REJECTED/ON_HOLD, 72h/30-day SLA, legal hold). `BaseEntity` tables, `ApiResponse` envelope, Flyway **V140–V145**, full Javadoc.
- **ACCESS export** = synchronous aggregation through owners' published `api` read ports via a registered **`SubjectExportContributor`** SPI (privacy ships the identity + consent contributors; others = CENTRAL NEEDS). Self-service (bound to `CurrentUser`) or ADMIN/ROOT on a tracked DSR; audited.
- **ERASURE** = publish **`ERASURE_REQUESTED`** outbox event (ids only, NO PII). The **IDENTITY erasure handler** crypto-shreds `idNo` + `idHash`, nulls profile/user PII, deletes `ProfileLocation`s, tombstones the account, and **appends** the `IDENTITY_ERASED` audit tombstone — **never breaking the hash-chain**, keeping the de-identified civic record. Idempotent (at-least-once safe). Active-role accounts cannot self-erase (note ᵇ).
- **Method-secured** (subject self-service `isAuthenticated()` + `ADMIN`/`ROOT` oversight, deny-by-default) and **fully audited** (`PRIVACY_CONSENT_CHANGED`, `PRIVACY_DSR_RECEIVED`, `PRIVACY_DSR_EXPORTED`, `PRIVACY_ERASURE_REQUESTED`, `IDENTITY_ERASED`). No secrets in source; no PII in events/logs (PRD §18, PDPA 2022/2023).
