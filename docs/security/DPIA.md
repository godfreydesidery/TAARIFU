# Taarifu — Data Protection Impact Assessment (DPIA)

| | |
|---|---|
| **Owner** | Salim Juma — Senior Security & Privacy Engineer |
| **Legal frame** | **Tanzania Personal Data Protection Act, 2022** + the **Personal Data Protection (Personal Data Collection and Processing) Regulations, 2023**; oversight by the **Personal Data Protection Commission (PDPC)** |
| **Scope** | Personal data processed by the Taarifu backend as implemented on `develop` |
| **Companion** | [THREAT-MODEL.md](THREAT-MODEL.md) (STRIDE + launch gate); PRD §18, §21 (DI5/EI-19), §24.4, §25.1, §26 (R10–R13, R27) |
| **Status** | Draft for **Legal/privacy sign-off** — a launch gate item (G21). Decisions on residency, lawful basis for private-responder sharing, and NIDA access terms are **routed to Legal**, not invented here. |

> **Why a DPIA.** Taarifu processes **national-ID / voter-ID data, MSISDN, precise location, and citizen reports about authorities** — high-risk processing of sensitive personal data at population scale, exactly the trigger for a DPIA under the PDPA/2023 Regulations. A national-ID breach (R10) is catastrophic for citizen safety, trust, and PDPA standing. This document is column-level, not slideware.

---

## 1. Roles, parties & processing context

- **Data controller:** Taarifu (the platform operator). Carries the **data-controller registration** obligation with the PDPC (R27 — start early with Legal).
- **Data processors / recipients (DI5, minimised field set per provider):**
  - Identity verification provider — MVP **operator-assisted (in-house Moderator)**; NIDA/voter registry **later** (no launch dependency, D-Q2). PII shared only when a real registry adapter lands → security+Legal review (TR-10).
  - SMS/USSD aggregator (MSISDN egress), email/SMTP ESP, push (FCM device token), object store (media), KMS/Vault (key material), analytics sink (**pseudonymised — no raw PII**).
  - **Responders** (government / parastatal / **private** companies) — receive report content; **private-responder sharing requires a lawful basis + citizen consent** (§24.4).
- **Data subjects:** citizens (T0–T3), representatives, organisation members, responder staff, moderators/admins.
- **Cross-border:** **in-country-where-feasible, cloud-portable** hosting (D-Q9). Residency for ID-bearing data and any cross-border processor must be **confirmed with Legal** before launch.

---

## 2. PII data inventory (data map)

Sensitivity: **S3** = special/high-risk (national/voter ID, precise location, sensitive reports) · **S2** = identifying PII · **S1** = pseudonymous/derived.

| # | Data element | Class | Stored where | At-rest protection | Lawful basis (PDPA) | Retention | On erasure (§25.1) |
|---|---|---|---|---|---|---|---|
| D1 | **National ID / voter ID** (`idNo`, `idType`) | **S3** | `profile.id_no` | **Field-level AES-GCM encryption** (`EncryptedStringConverter`); prod KMS envelope owed (TR-2) | **Consent** + legal-obligation-adjacent identity assurance for high-trust civic actions | Life of account | **Severed + tombstoned** (`anonymized_user_#`) |
| D2 | **ID blind index** (`id_hash`) | S3 (derived) | `profile.id_hash` (unique) | Keyed HMAC-SHA-256; non-reversible; backs one-account dedup (D15) | Same as D1 (integrity/dedup) | Life of account | Cleared with D1 |
| D3 | **Verification evidence** (document/selfie refs) | **S3** | object store + `VerificationRequest` | Signed URLs; access-scoped; quarantine | Consent (verification) | Short (review window) | Deleted/redacted |
| D4 | **MSISDN / phone** (unique) | **S2** | `user.phone` | DB access control; not field-encrypted (used as login + uniqueness key) | Contract (account) + consent (OTP/SMS) | Life of account | Severed + tombstoned |
| D5 | **Email** | S2 | `user.email` | DB access control | Contract + consent | Life of account | Severed + tombstoned |
| D6 | **Credentials** (`password_hash`, TOTP secret) | S2 | `user` | **BCrypt** hash; TOTP secret protected | Contract (account security) | Life of account | Deleted |
| D7 | **ProfileLocation** (home/residence/GPS pins) | **S3** | `profile_location` | DB access control; **never public** (§9.0) | Consent (location + civic routing) | Life of account | **Deleted** |
| D8 | **Names, DOB, gender, nationality** | S2 | `profile` | DB access control | Consent / contract | Life of account | Severed + tombstoned |
| D9 | **Report content + incident location** | S2 (S3 if sensitive) | `report`, `case_event` | Access control; sensitive forced PRIVATE; coarsened location (§25.3) | Consent / public-interest civic reporting | Civic record (long-lived) | Reporter **de-identified**, content kept (PII redacted) |
| D10 | **Attachments / media** | S2 | `media_object` + object store | Signed URLs; malware scan; **EXIF/geo strip** | Consent | Tied to host entity | Deleted/redacted if PII |
| D11 | **Binding civic actions** (signatures, ratings) | S2 → S1 | engagement / accountability | Access control | Consent (T3) | Civic record | **De-identified; count preserved** |
| D12 | **OTP / sessions / refresh tokens** | S2 | `otp_challenge`, `refresh_token` | Short-lived; rate-limited; hashed keys | Contract (auth) | Minutes–days (auto-expire) | Auto-expire |
| D13 | **Audit log** | S1 | `audit_event` | **References/hashes only — never raw PII**; hashed client IP; hash-chained | Legal obligation (accountability) | Long (compliance) | **Tombstone event appended; never deleted** |
| D14 | **Analytics events** | S1 | analytics sink | **Pseudonymised from creation — no raw PII** (EI-9) | Legitimate interest (product) | Per analytics policy | N/A (no raw PII) |
| D15 | **MSISDN at aggregator / device token (FCM)** | S2 | external processor | TLS in transit; minimised | Consent (notifications) | Per processor contract | Unregister / prune |

---

## 3. PDPA principle mapping (per data category)

| PDPA principle | How Taarifu satisfies it | Evidence / gap |
|---|---|---|
| **Lawful, fair, transparent** | Tiered consent at signup + per-action; privacy center; citizen told **who** their report goes to before submit (§24.4) | ✅ design; consent-center UX to confirm |
| **Purpose limitation** | Each tier/action collects only what it needs; D5/DI5 minimised field set per provider | ✅ design |
| **Data minimisation** (R13) | T1 needs only phone/email; ID (D1) only at T3; `ProfileLocation` derived from one pin; outbox/analytics carry **no raw PII** | ✅ `OutboxEvent`, analytics pseudonymisation |
| **Accuracy** | Effective-dated geography; voter-ID-authoritative electoral; verification flags | ✅ |
| **Storage limitation** | Per-entity retention table (§25.1); OTP/sessions auto-expire; legal hold suspends erasure for investigations | ✅ design; admin-configurable retention to confirm |
| **Integrity & confidentiality** | Field-level encryption (D1), BCrypt (D6), TLS, deny-by-default RBAC, immutable hash-chained audit, PII redaction | ✅ baseline; **prod KMS owed (TR-2)** |
| **Accountability** | Controller registration (R27), this DPIA, audit trail, RoPA-style inventory (§2) | ⚠️ registration + Legal sign-off owed |

---

## 4. Data-subject rights (PDPA)

| Right | Implementation (PRD) | Status |
|---|---|---|
| **Access** | Right-to-access (UC-A17/UC-S09); profile + privacy center export | ⚠️ verify export completeness |
| **Erasure** | **Anonymisation, not row deletion** — PII severed + tombstoned; civic record de-identified; counts preserved (§25.1). Reconciles erasure ↔ immutable audit ↔ one-account permanence | ✅ design; **build + verify (TR-7-adjacent)** |
| **Rectification** | Profile edit; verified identity fields locked, changes audited | ✅ |
| **Object / restrict** | Notification/data-sharing consent toggles; per-location prefs | ⚠️ confirm granularity |
| **Erasure SLA** | Acknowledge ≤72h, complete ≤30 days; **legal hold** suspends for investigations/sensitive reports | ✅ policy; operationalise |

**Erasure invariant (key design):** erasure **never mutates** the audit log — it appends an `IDENTITY_ERASED` tombstone (`AuditEvent`). This keeps the hash-chain intact while removing the person, satisfying both PDPA erasure and tamper-evident accountability.

---

## 5. High-risk processing — specific safeguards

- **National/voter ID (D1, R10):** the single highest-risk asset. Field-encrypted, decrypted only on lawful need, never logged, dedup via blind index without decryption, **no national-ID data in non-prod** (synthetic/masked only, §25.1 N8). **Prod KMS envelope + rotation is a P1 launch blocker (TR-2/G13).**
- **Anonymous sensitive reports (D9, §25.3, R20):** no `reporterProfile` stored; pseudonymous tracking token; forced PRIVATE; incident location **coarsened/redacted** in shared/aggregate views; attachments EXIF/geo-stripped; GBV duty-of-care referral path; reporter never exposed to the report's subject. **End-to-end verification owed (G17/TR-7).**
- **Private-responder data-sharing (§24.4):** sharing a PII-bearing report to a **private** company requires a **lawful basis + explicit citizen consent**; provider sees the **minimum** needed; cross-sector sharing logged; sensitive categories stricter. **Lawful basis to be confirmed with Legal** (Phase 2).
- **Non-prod data (N8):** staging/test/demo use **synthetic or masked** data only; the dev bootstrap admin carries **no PII** (`DevAdminSeeder`).

---

## 6. Residual privacy risks & Legal sign-off (gate G21)

| ID | Residual privacy risk | Trace | Owner | Action before launch |
|---|---|---|---|---|
| **DP-1** | Prod KMS envelope encryption + key rotation not wired (dev static key) | R10, TR-2 | Eng/SRE | **P1 blocker** — wire EI-19 KMS adapter |
| **DP-2** | Data-controller **registration with PDPC** not confirmed | R12/R27 | Legal | Register before processing real citizen PII |
| **DP-3** | **Residency / cross-border** for ID data + any cross-border processor unconfirmed | R12, D-Q9 | Legal | Confirm in-country hosting / lawful transfer basis |
| **DP-4** | **Lawful basis for private-responder PII sharing** unsettled | §24.4 | Legal | Define basis + consent UX (Phase 2) |
| **DP-5** | **NIDA access terms** unsettled — egress field set, logging, residency | EI-1/2, TR-10 | Legal/Security | Mandatory review when real adapter lands |
| **DP-6** | Erasure + access **export** completeness not yet verified end-to-end | §25.1 | Eng | Build + test DSAR flows |
| **DP-7** | Consent-center granularity (per-purpose, per-sharing) to confirm | §18 | Product/Legal | Confirm consent records are auditable |

**Privacy go / no-go:** **NO-GO pending Legal/privacy sign-off (G21).** The engineering baseline (field-level encryption, blind-index dedup, no-PII outbox/analytics, hashed-IP immutable audit, anonymisation-based erasure) is PDPA-aligned by design. Outstanding items are **prod KMS (DP-1)**, **PDPC registration (DP-2)**, **residency (DP-3)**, **private-sharing lawful basis (DP-4)**, and **DSAR build/verify (DP-6)** — none invented here; residency, lawful basis, and NIDA terms are explicitly **routed to Legal**.

---

*Companion: [THREAT-MODEL.md](THREAT-MODEL.md) — STRIDE trust-boundary analysis, ranked residual-risk register, and the full launch security-gate checklist.*
