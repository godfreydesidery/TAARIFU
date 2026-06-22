# Taarifu — Product Requirements Document (PRD)

| | |
|---|---|
| **Product** | Taarifu — Tanzania Civic Engagement Platform |
| **Document** | Product Requirements Document v1.0 (DRAFT) |
| **Date** | 2026-06-22 |
| **Author** | Engineering (clean-rewrite initiative) |
| **Status** | Draft — **all key product decisions resolved (§19); ready for development approval.** Supersedes the existing `taarifu-engine-api`, `taarifu-engine-dash`, `taarifu-mob-app`, `taarifu-core-api` codebases |
| **Related** | [SYNOPSIS.md](SYNOPSIS.md) (analysis of the existing systems) |

> **How to read this document.** This PRD designs the target system *forward*. The four existing repositories are used only as **insight** into intent — none of their code, schema, or API contracts are binding. Where the existing code informs a decision, it is cited as *(insight)*. Locked product decisions (confirmed with the product owner) are marked **[LOCKED]**. Assumptions the team can still override are marked **[ASSUMPTION]**. Items needing a decision are collected in §19.

---

## Table of Contents

1. Executive Summary
2. Vision, Problem & Objectives
3. Goals, Non-Goals & Success Metrics
4. Locked Decisions & Assumptions
5. Personas
6. Actors & the Actor Model
7. Roles, Permissions & Trust Tiers (RBAC)
8. Scope: Capability Pillars & Release Phasing
9. Domain Model (target design)
10. Functional Requirements — Epics, User Stories & Acceptance Criteria
11. Use-Case Catalogue (exhaustive) + detailed flows
12. Status Workflows & State Machines
13. Notifications & Channels Matrix
14. Clients & Channels (mobile, web, USSD/SMS, admin)
15. Non-Functional Requirements
16. System Architecture
17. API Design Principles
18. Security, Privacy & Compliance
19. Resolved Decisions (development gate)
20. Glossary

**Part II — Extended Specification (v1.1)**
21. Dependencies & External Integrations
22. Search & Discovery
23. Tokens, Credits & Wallet
24. Service Providers & Multisectoral Responders
25. Edge-Case & Gap Closures (data lifecycle, routing fallback, anonymity, `isElectoral`, tiers, errors, abuse, moderation ops)
26. Risks & Mitigations
27. Rollout, Milestones & Data Migration
28. Key End-to-End Journeys

**Appendices**
- Appendix A: RBAC Permission Matrix (excerpt) · Appendix F: Full RBAC Matrix
- Appendix B: Reference Data & Seed
- Appendix C: Analytics & KPIs catalogue
- Appendix D: Issue Category Taxonomy, SLA & Routing
- Appendix E: Analytics Event Taxonomy & Measurement Plan

---

## 1. Executive Summary

**Taarifu** ("taarifu" = Swahili for *report / inform / notify*) is a **civic engagement platform for Tanzania** that connects **citizens** with their **elected representatives** and **government authorities**, organised around the country's full administrative and political geography (Region → District → Ward → Village/Mtaa → Hamlet/Kitongoji, plus Constituency).

The platform delivers four capability pillars:

1. **Issue Reporting & Case Management** — citizens report local problems (water, roads, health, safety, services, corruption); reports are routed to the responsible authority and tracked to resolution with SLAs.
2. **Representatives & Institutions** — verified profiles of MPs/councillors linked to constituencies, parties and parliament, with accountability data (contributions, attendance, promises, project delivery, citizen ratings).
3. **Engagement** — petitions, polls/surveys, public Q&A to representatives, and moderated discussion.
4. **Announcements & Notifications** — authorities and representatives publish geo-targeted information; citizens receive a personalised feed via push and SMS.

Identity uses a **tiered verification model**: anyone can sign up with a phone/email and browse; completing a profile and verifying a **national ID (NIDA) / voter ID** unlocks higher-trust actions (filing official reports, signing petitions, rating representatives). Responders are both **government agencies/area officials** and **elected representatives**, with **moderators** ensuring trust and safety.

The product is delivered as a **Flutter mobile app** (primary citizen channel), a **responsive web app/PWA** (citizen + representative + organisation), an **Angular admin console** (authorities, moderators, administrators), and **USSD/SMS** for feature-phone reach — all backed by a **Spring Boot (Java 21) modular backend** on **PostgreSQL**.

---

## 2. Vision, Problem & Objectives

### 2.1 Vision
> A Tanzania where every citizen — regardless of device, language, or location — can see who represents them, hold them accountable, report what is broken in their community, and be heard by the authorities responsible for fixing it.

### 2.2 Problem statement
- Citizens lack a single, trustworthy channel to **reach the right authority** for a local problem and **track** whether anything is done.
- Information about **representatives and their record** (what an MP has done, which development projects exist in a ward, how budgets are spent) is fragmented, offline, or inaccessible.
- Authorities lack a structured **intake and case-management** tool for citizen issues, and lack **geo-targeted** channels to inform constituents.
- Existing attempts (the four prior repos) built a **reference-data catalogue and auth shells** but never the civic interaction layer; identity, accountability, reporting and notifications are unbuilt or stubbed *(insight)*.

### 2.3 Strategic objectives
1. **O1 — Reduce friction to be heard:** a citizen can file a well-routed report in < 2 minutes on any channel.
2. **O2 — Make representation transparent:** every citizen can find their representatives and see an accountability record.
3. **O3 — Close the loop:** authorities resolve and respond; citizens see status changes and outcomes.
4. **O4 — Inclusive reach:** usable on low-end smartphones, feature phones (USSD/SMS), in **Swahili and English**, at low data cost.
5. **O5 — Trust by design:** verified identities for high-stakes actions, strong moderation, auditable actions, privacy-respecting.

---

## 3. Goals, Non-Goals & Success Metrics

### 3.1 Goals (in scope)
- A complete civic platform spanning the four pillars **[LOCKED]**.
- Tiered identity & verification **[LOCKED]**.
- Government-agency **and** representative responders + moderators **[LOCKED]**.
- Multi-channel: mobile, web/PWA, admin console, USSD/SMS, push.
- Bilingual (Swahili default, English).

### 3.2 Non-Goals (explicitly out, at least for v1)
- **Running elections / official voting / vote tallying.** Taarifu informs and engages; it is not an electoral system.
- **Fundraising / donations / fines / e-commerce.** Out of scope. *(Updated by D19: **token purchase via mobile money enters scope in Phase 2** — §23; no money movement in MVP, and never fundraising/fines.)*
- **Being a system of record for government finance.** Project/budget data is *published* to Taarifu, not authored as the financial source of truth.
- **Anonymous whistleblowing as a primary mode.** Anonymous/low-trust reporting may be allowed for some categories (see §19 Q5) but is not the core identity model.
- **Social-network features** beyond civic engagement (no general friend graph, DMs between citizens, media sharing unrelated to civic content).

### 3.3 Success metrics (KPIs)
| Theme | Metric | Target (12 mo post-launch) |
|---|---|---|
| Adoption | Monthly active citizens | Region-pilot dependent; growth MoM > 10% |
| Verification | % active citizens ID-verified | ≥ 40% |
| Reporting | Reports filed / month; median time-to-first-response | TTFR < 48h |
| Resolution | % reports resolved; median time-to-resolution | ≥ 60% resolved; TTR < 30 days |
| Responsiveness | % representative Q&A answered within 14 days | ≥ 50% |
| Engagement | Petition signatures, poll responses / active user | trend up |
| Reach | % sessions via USSD/SMS; % Swahili usage | measured; inclusive |
| Trust | % content auto/▸manually moderated; abuse report rate | abuse < 2% of content |
| Reliability | API availability; p95 latency | 99.9%; < 500ms |

---

## 4. Locked Decisions & Assumptions

### 4.1 Locked decisions (product owner confirmed)
- **[LOCKED] L1 — Core purpose:** Comprehensive civic platform (all four pillars), delivered in phases.
- **[LOCKED] L2 — Pillars in scope:** (1) Issue reporting & case management; (2) Representatives & institutions; (3) Engagement (petitions/polls/Q&A); (4) Announcements & notifications.
- **[LOCKED] L3 — Identity:** Tiered verification — phone/email + OTP to start; optional NIDA/voter-ID verification unlocks higher-trust actions.
- **[LOCKED] L4 — Responders:** Government agencies/area officials **and** elected representatives, plus moderators.

### 4.2 Assumptions (overridable)
- **[ASSUMPTION] A1 — Geography:** Tanzania, full hierarchy *Region → District → Constituency / Ward → Village(Mtaa) → Hamlet(Kitongoji)*; reference data seeded from official sources.
- **[ASSUMPTION] A2 — Languages:** Swahili (default) + English at launch; architecture supports adding more.
- **[ASSUMPTION] A3 — Channels:** Flutter mobile (primary), responsive web/PWA, Angular admin console, and USSD/SMS for feature phones; push (FCM) + SMS notifications.
- **[ASSUMPTION] A4 — Stack:** Spring Boot (Java 21) modular monolith → PostgreSQL, Redis, object storage (S3-compatible), event bus; Flyway migrations; OpenAPI. Modernises the existing Spring/Angular/Flutter investment *(insight)*.
- **[DECIDED] D3 — Representative scope:** Launch covers **MPs + Councillors (Madiwani) + ward/village executive officers** — full local-government leadership. The generic `Representative` model carries a `type`. *(Ambitious: onboarding & verifying local leaders nationwide is a sizable program workstream — see §19 and Risks.)*
- **[ASSUMPTION] A6 — Hosting:** Containerised, dev/staging/prod, CI/CD, observability per `deploy.md` north-star *(insight)*; can start on a single managed VM and grow.

---

## 5. Personas

> Personas are illustrative; **Actors** (§6) are the normative list.

- **P1 — Amina, 29, Dar es Salaam, smartphone.** Wants to report a broken water point and see if her MP is doing anything about the ward’s road project. Comfortable in Swahili, low data budget.
- **P2 — Joseph, 54, rural Singida, feature phone.** No smartphone; uses USSD/SMS. Wants to report and to receive alerts about his village.
- **P3 — Hon. Neema, Member of Parliament.** Wants to publish updates to constituents, answer questions, and show her record (contributions, projects delivered).
- **P4 — Mr. Mushi, District Water Officer (Area Official).** Receives water-related reports for his district, updates status, resolves cases, reports back.
- **P5 — Faith, Civic Organisation (NGO) coordinator.** Runs awareness campaigns, creates petitions, aggregates community issues.
- **P6 — Dr. Salum, Moderator (Trust & Safety).** Reviews flagged content, handles abuse, verifies identities and representative claims.
- **P7 — Grace, Platform Administrator.** Manages reference data, onboards authorities/representatives, configures categories/SLAs, monitors health.

---

## 6. Actors & the Actor Model

### 6.1 Human actors
| ID | Actor | Description | Primary channel |
|---|---|---|---|
| AC1 | **Guest / Visitor** | Unauthenticated; can browse public content (representatives, projects, announcements, public issues), search, and read. | Mobile, Web, USSD |
| AC2 | **Citizen (Tier 0–2)** | Registered individual. Trust tier governs actions (see §7.3). | Mobile, Web, USSD/SMS |
| AC3 | **Organisation** | Verified civic org/NGO/community group; a profile of type ORGANIZATION with org-level capabilities (campaigns, petitions, aggregated reporting). | Web, Mobile |
| AC4 | **Representative** | Elected/local leader — **MP (Mbunge), Councillor (Diwani), and ward/village executive officer** at launch. Publishes updates, answers Q&A, responds to petitions, manages own accountability profile. | Web, Mobile |
| AC5 | **Responder / Service Provider** | Officer of a **responding organisation** — government agency, **parastatal** (TANESCO, DAWASA), or **private company** (banks, telecoms) — scoped to sectors/categories + areas. Receives, assigns, resolves reports (incl. multisectoral); publishes announcements. Generalises the "Area Official" (§24, D20). *(insight: AreaAdmin ↔ Areas)* | Admin console, Web |
| AC6 | **Moderator** | Trust & safety: reviews flagged content, handles abuse, verifies IDs and representative claims, manages takedowns/appeals. | Admin console |
| AC7 | **Administrator** | Platform operations: reference data, taxonomies (categories, SLAs), onboarding authorities/representatives, configuration, analytics. | Admin console |
| AC8 | **Super Admin (Root)** | Highest privilege: system config, admin management, security settings, irreversible operations. | Admin console |

### 6.2 System actors (non-human)
| ID | Actor | Role |
|---|---|---|
| SY1 | **Notification Service** | Sends push/SMS/email; manages preferences, batching, retries. |
| SY2 | **SMS/USSD Gateway** | Inbound/outbound SMS and USSD session handling (aggregator integration). |
| SY3 | **Identity Verification Provider** | Verifies NIDA/voter ID & phone (NIDA API or manual/operator-assisted fallback). |
| SY4 | **Scheduler / Workflow Engine** | SLA timers, escalations, digests, reminders, data syncs. |
| SY5 | **Search Index** | Full-text & geo search over public entities. |
| SY6 | **Media/Object Store** | Stores attachments (photos, documents) with virus scan & EXIF/geo handling. |
| SY7 | **Analytics/Event Pipeline** | Captures events for dashboards, KPIs, and audit. |

### 6.3 Actor model (design)
A clean separation of **account**, **identity**, and **civic role** — informed by `core-api`’s Profile-as-hub composition, redesigned for clarity *(insight)*:

```
Account (auth)            Identity                     Civic Role(s)
─────────────            ─────────                    ─────────────
User ───1:1──► Profile ─────────────┐                 Citizen        (verified individual)
 - credentials  - person/org PII    ├─ holds 0..n ──► Organization   (org membership/admin)
 - status       - NIDA/voter ID      │                Representative (MP/Councillor)
 - roles[]      - contacts           │                AreaOfficial   (agency, scoped to areas/categories)
 - trustTier    - verification flags │                Moderator / Admin / Root (staff roles)
                - locations (multi; 1 primary + 1 electoral)
```

- **User** = authentication account: credentials, status, assigned **roles[]** and computed **trustTier**.
- **Profile** = the human/organisation identity: names, contacts, government ID (`idNo` + `idType` ∈ {NATIONAL, VOTER, …}), verification flags (`idVerified`, `emailVerified`, `phoneVerified`), demographics, and **multiple geographic associations** (see §9.0 — a profile may link to several places with an association type; exactly one is **primary** and one is the **electoral** location) *(insight: ProfileConstituency, generalised)*.
- **Civic roles** attach capabilities. A single account may hold more than one role (e.g. an MP is also a Citizen). **Staff roles** (AreaOfficial, Moderator, Admin, Root) are granted, not self-served.
- **Design fix vs. existing code:** replace the prior parallel `id + uid + code` triple and copy-pasted audit with a single **shared base** (UUID public id, sequence-based human codes where needed, common audit/soft-delete) and **real foreign keys** instead of denormalized `Long` id fields *(insight: existing code had loose `constituencyId` longs and no FK)*.

### 6.4 Single account, additive roles **[DECIDED]**
**Principle: one person → one account → one profile; roles accrue over time. Identity is permanent; roles are additive and time-bounded.**

- **D11 — One account per person.** A person never has two accounts. Uniqueness is enforced at the **person** level: **one account per phone** at signup (OTP), and at **ID verification** any duplicate using the same national/voter ID is **blocked or merged**. Email alone is *not* a uniqueness key (a person may have several).
- **D12 — Roles accrue on the same account.** When a Citizen is **elected MP**, an Admin **grants the `REPRESENTATIVE` role** to their *existing* account after verifying official results, and links constituency/party/term. **Same login, same profile, reused verified identity — no re-registration.** They keep the Citizen role (they can still report issues / follow their home ward) and gain MP capabilities.
- **Role lifecycle.** Roles have their own status independent of the account: e.g. Representative `PENDING_VERIFICATION → SITTING → FORMER`. When a term ends, the Representative becomes `FORMER` (history retained) but the **account and Citizen role persist**; re-election re-activates on the same account. AreaOfficial, Organization-member, Moderator and Admin attach the same way.
- **Context switcher.** Clients let a multi-role user act in the correct “hat” (e.g. post *as MP* vs report an issue *as a citizen*); the active context is recorded on each action.
- **D13 — Conflict-of-interest guardrails.** No actor may act on themselves or their own work: a Representative cannot rate/sign-a-petition-against/answer-a-question-targeting **themselves**; an Area Official cannot resolve **their own** report; a Moderator cannot moderate **their own** content. Staff may use citizen features, but all multi-hat actions are **audited**.

> Net: the account is a stable identity that **gains and loses capabilities** over a civic lifetime — citizen today, MP tomorrow, former-MP-and-citizen later — without ever fragmenting the person’s history.

---

## 7. Roles, Permissions & Trust Tiers (RBAC)

### 7.1 Principles
- **Role-Based Access Control with attribute scoping.** Permissions are granted to **roles**; some roles are **scoped** (an Area Official only acts within assigned areas/categories; a Representative only manages their own constituency/profile).
- **Method-level enforcement** on every protected endpoint (no “authenticated-only” admin surface). *(insight: prior engine-api left `@PreAuthorize` ineffective; core-api enabled it but missed activity controllers — both fixed here.)*
- **Least privilege**; **deny by default**; **server-side** authorization (never trust the client role claim alone).

### 7.2 Role catalogue
| Role | Scope | Granted by |
|---|---|---|
| GUEST | global (read public) | implicit |
| CITIZEN | self | self sign-up |
| ORGANIZATION_MEMBER / _ADMIN | organisation | org admin / platform |
| REPRESENTATIVE | own profile + constituency | Admin (after verification) |
| AREA_OFFICIAL | assigned areas + categories | Admin |
| MODERATOR | global content | Admin |
| ADMIN | global config | Root/Admin |
| ROOT | global system | Root |

### 7.3 Citizen trust tiers (tiered verification) **[LOCKED]**
| Tier | Requirement | Unlocks |
|---|---|---|
| **T0 — Guest** | none | Browse, search, read public content, view representatives/projects/announcements |
| **T1 — Registered** | phone **or** email + OTP | Follow representatives/areas, subscribe to feed, save items, take anonymous-eligible polls, submit *community* reports (lower-trust, may be rate-limited/queued) |
| **T2 — Profiled** | T1 + complete profile (name, geo, contacts), email/phone verified | File official issue reports, comment in moderated discussions, ask Q&A, respond to surveys |
| **T3 — ID-Verified** | T2 + NIDA/voter-ID verified | Sign petitions, rate representatives, vote in binding polls, create organisation, elevated rate limits |

> Mapping to a single computed `trustTier` on the account; gating is enforced server-side per action. Verification can be **automated** (provider API) or **operator-assisted** (Moderator approves evidence) — see §18.

### 7.4 Permission matrix
See **Appendix A** for the full action × role matrix.

---

## 8. Scope: Capability Pillars & Release Phasing

The four pillars are decomposed into modules. Phasing balances “comprehensive” intent with a shippable path.

| Module | Pillar | Phase |
|---|---|---|
| M0 Identity, Onboarding & Verification | foundation | **MVP** |
| M1 Geography & Reference Data | foundation | **MVP** |
| M2 Representatives & Institutions (profiles, find-my-rep) | 2 | **MVP** |
| M3 Issue Reporting & Case Management | 1 | **MVP** |
| M4 Announcements & Personalised Feed | 4 | **MVP** |
| M5 Notifications (push/SMS/email) | 4 | **MVP** |
| M6 Representative Accountability (contributions, attendance, promises, ratings) | 2 | Phase 2 |
| M7 Projects (development/CDF tracking) | 2 | Phase 2 |
| M8 Surveys & Polls | 3 | Phase 2 |
| M9 Petitions | 3 | Phase 2 |
| M10 Q&A to Representatives | 3 | Phase 2 |
| M11 Discussions & Comments | 3 | Phase 2/3 |
| M12 Moderation, Trust & Safety | cross-cutting | **MVP** (basic) → Phase 2 (full) |
| M13 USSD/SMS Channel | cross-cutting | Phase 2 (parallel pilot) |
| M14 Admin Console & Configuration | cross-cutting | **MVP** |
| M15 Analytics & Reporting | cross-cutting | Phase 2 |
| M16 Organisations & Campaigns | actor | Phase 3 |
| M17 Tokens, Credits & Wallet (§23) | cross-cutting | **MVP** (free) → Phase 2 (purchase) |
| M18 Service-Provider Directory & Multisectoral Reports (§24) | actor/reporting | **MVP** (govt/parastatal) → Phase 2 (private/B2B) |

**Decided scope expansions (affect MVP load):**
- **Representatives at MVP = full local government** (MPs + Councillors + ward/village executive officers), not just MPs (D3). The platform model handles this from day one; the **onboarding/verification program** is the gating constraint, not the software.
- **Responders at launch = nationwide government-agency onboarding** (D5). The case-management software is launch-ready; **nationwide department participation is a partnership-dependent program workstream** and is the main delivery risk to the “close-the-loop” KPIs. *Recommendation: ship the full software capability but stage real onboarding region-by-region so the resolution loop is proven where officials are live.*

**MVP definition of done:** a verified citizen can find their representatives (MP/councillor/local exec), file and track an issue routed to the responsible area official, receive announcements/notifications for their area, all in Swahili/English on mobile + web, with admin onboarding of geography, categories, officials and representatives, and basic moderation.

---

## 9. Domain Model (target design)

> Designed forward. UUID public ids; internal numeric PKs allowed; **real FKs**; shared audit (`createdAt/By`, `updatedAt/By`), soft-delete (`deleted`, `deletedAt/By`), optimistic locking. Human-readable codes via DB sequences where needed (e.g. ticket numbers).

### 9.0 Civic Geography & Location model (foundational) **[DECIDED]**

Tanzania has **two overlapping geographies**, and a citizen relates to a place through both. A citizen **pins a physical place** (GPS or a ward/village); the system **derives both chains** from it — they never type a constituency.

```
                Pinned place  (GPS, or pick a Ward/Mtaa; deeper optional)
                       │
        ┌──────────────┴───────────────┐
        ▼                              ▼
 ADMINISTRATIVE chain            ELECTORAL mapping
 Region → District → Council     Ward ──belongs to──► Constituency (Jimbo)
 (LGA/Halmashauri) → [Division]
 → Ward (Kata) → Village(Kijiji)
 /Mtaa → Hamlet (Kitongoji)
        │                              │
        ▼                              ▼
 Ward → Councillor (Diwani);     Constituency → MP (Mbunge)
 Council/District officials
 (report routing & services)
```

- **D6 — Administrative hierarchy:** `Region → District → Council/LGA (Halmashauri) → [Division/Tarafa, optional] → Ward (Kata) → Village (Kijiji) / Mtaa → Hamlet (Kitongoji)`. **Council/LGA is added** vs the old model because services/officials sit there. A unified `LocationType` + queryable hierarchy (closure table / materialised path) replaces the prior denormalized `Area` mirror.
- **Electoral mapping:** a `WardConstituency` bridge maps each ward → its constituency, **effective-dated** so re-delimitation/new districts don’t corrupt history. Constituency → MP; Ward → Councillor. *(insight: resolves the old Region-vs-District-vs-Constituency ambiguity.)*
- **Minimum pin = Ward** (enough to derive councillor + constituency + report routing); village/hamlet optional for precision; GPS auto-resolves to ward.

**Multiple locations, one primary, one electoral.** A profile holds **many** `ProfileLocation`s (e.g. *home = Rombo*, *residence = Dar*), each typed by `associationType`. Two singleton flags:
- **`isPrimary`** — default context (default feed, default report area, profile headline). Set at registration (the single residence captured at signup — *Option A*); others added later in profile.
- **`isElectoral`** — the **one** location that carries **binding civic weight**. Defaults to primary; set **authoritatively by voter-ID verification** (`IdType.VOTER`); manual change is **rate-limited (cooldown)** and audited to prevent gaming.

**Action scoping by location (integrity rule):**
| Action class | Scope |
|---|---|
| Browse, follow, feed, announcements, view reps/projects | **All** the citizen’s locations |
| Report an issue, ask question, comment, non-binding survey | The relevant location (report routes by **incident** location) |
| **Rate an MP, sign a constituency petition, vote in a binding poll** | **Only** the single **electoral** location |

**Edge cases handled by the model:** non-constituency MPs (**special seats / nominated**) — `Representative.mandate`; **diaspora** — residence may be non-TZ while electoral/home stays a real TZ ward; **per-location notification preferences**; `ProfileLocation` is **private PII** (never shown publicly). **Zanzibar** (its own admin structure + House of Representatives) is **Phase 2** (D17); the generic model already supports it.

### 9.1 Core aggregates
**Identity & Access**
- `User` (account): id, username/handle, email, **phone (unique — one account per phone)**, passwordHash, status {PENDING, ACTIVE, SUSPENDED, DISABLED}, **roles[] (additive)**, trustTier, lastLoginAt, mfaEnabled. **One account per person**, enforced by unique phone at signup + national/voter-ID dedup at verification (§6.4).
- `Profile`: type {PERSON, ORGANIZATION}, names, idNo+idType {NATIONAL, VOTER, PASSPORT…}, contacts, demographics (dob, gender, nationality), verification {idVerified, emailVerified, phoneVerified, verifiedAt, verifiedBy}, status.
- `ProfileLocation` (private PII; many per profile): profile → pinned place (ward/village/hamlet) which resolves the admin chain + constituency; `associationType` {HOME/ANCESTRAL, RESIDENCE, WORK, FAMILY, BUSINESS, PROPERTY, INTEREST}, `isPrimary` (exactly one), `isElectoral` (exactly one; voter-ID-authoritative, change-cooldown). See §9.0. *(insight: ProfileConstituency, generalised across levels)*
- `Role`, `Permission`, `RoleAssignment` (scoped: areaIds[], categoryIds[], constituencyId).
- `VerificationRequest`: subject, type {ID, REP_CLAIM, ORG}, evidence, status, reviewer.
- `AuditLog`, `Session`/`RefreshToken`.

**Geography (reference)** — see §9.0 for the model.
- Administrative: `Region → District → Council/LGA (Halmashauri) → [Division/Tarafa] → Ward (Kata) → Village (Kijiji)/Mtaa → Hamlet (Kitongoji)`. Each: code, name, geo (lat/long/boundary optional), population, status, parent FK.
- `Constituency` (Jimbo): belongs to District; `WardConstituency` bridge maps wards → constituency (**effective-dated**). Constituency → MP; Ward → Councillor.
- Unified `LocationType` enumeration + a queryable hierarchy (closure table / materialised path) replacing the prior denormalized `Area` mirror.

**Institutions**
- `PoliticalParty`: code, name, abbreviation, ideology, foundedYear, logo, status, contacts.
- `Parliament`: term/session, startDate, endDate, isCurrent.
- `ParliamentRole`: e.g. Speaker, Minister, Committee chair (assignable to Representatives).
- `Representative`: profile (1:1, **same account** §6.4), type {MP, COUNCILLOR, WARD_EXEC…}, `mandate` {CONSTITUENCY, SPECIAL_SEATS, NOMINATED, COUNCILLOR_WARD}, constituency/ward (FK, **nullable** for special-seats/nominated), party (FK), legislature {UNION_PARLIAMENT, ZANZIBAR_HOR}, parliament/term, status {PENDING_VERIFICATION, SITTING, FORMER}, electedAt, bio. *(insight: redesigns the anemic `Mp` with real FKs + non-constituency mandates.)*

**Reporting & Cases (M3)**
- `IssueCategory` (hierarchical): name, parent, default routing (department/area level), default SLA, icon. e.g. Water, Roads, Health, Education, Security, Corruption, Electricity, Environment, Other.
- `Report` (ticket): code (e.g. `TAR-2026-000123`), reporterProfile (nullable if anonymous-eligible), category, title, description, location (geo point + admin area), attachments[], visibility {PUBLIC, PRIVATE}, status, priority, assignedOffice/official, dueAt, resolution, confirmation, duplicateOf, upvotes/followers.
- `CaseEvent` (timeline): statusChange, assignment, comment (public/internal), attachment, escalation.
- `Assignment`, `SLAClock`, `Escalation`.

**Engagement**
- `Petition`: title, body, target (representative/authority), signatureGoal, deadline, status {DRAFT, ACTIVE, SUCCEEDED, CLOSED, RESPONDED}, creatorProfile/org, response.
- `PetitionSignature`: petition, profile (T3), timestamp, comment, isPublic.
- `Survey`/`Poll`: title, description, type {SURVEY, POLL}, audienceScope (geo/role), questions[], options[], startsAt/endsAt, anonymity, status. *(insight: redesigns the empty `Survey` shell with questions/options/responses.)*
- `Question` (Q&A): asker (T2), target representative, body, status {OPEN, ANSWERED, DECLINED, MODERATED}, answer, upvotes.
- `Comment`/`Discussion`: threaded, attached to a host entity (report/announcement/project), moderation state.

**Accountability & Activities**
- `RepresentativeContribution`: representative (FK), type {SPEECH, MOTION, BILL, QUESTION, VOTE, COMMITTEE}, title, summary, date, parliamentSession, sourceUrl, attachments. *(insight: redesigns `MpContribution` with a real MP link + content.)*
- `Attendance`: representative, session, present/absent.
- `Promise`/`Pledge`: representative, text, madeAt, status {MADE, IN_PROGRESS, KEPT, BROKEN}, evidence, linkedProjects[].
- `Project` (development/CDF): code, name, description, sector, fundingSource (e.g. CDF), budget, status {PLANNED, ONGOING, COMPLETED, STALLED}, progress%, startDate/endDate, areas[] (FK), responsibleOffice/representative, milestones[], updates[]. *(insight: redesigns `Project` with budget/status/timeline/area FKs.)*
- `Rating`: subject {REPRESENTATIVE, OFFICE, PROJECT}, profile (T3), score, comment, period.

**Communications**
- `Announcement`: author (official/representative/org), title, body, audienceScope (geo + role), category, attachments, publishAt/expireAt, channels[] {FEED, PUSH, SMS}, status.
- `FeedItem` (materialised/derived per user from announcements, followed entities, nearby reports).
- `Notification`: recipient, type, payload, channel, status {QUEUED, SENT, DELIVERED, READ, FAILED}.
- `Subscription`/`Follow`: profile ↔ (area | representative | category | project | petition).
- `NotificationPreference`: per channel/type opt-in, quiet hours, language.

**Platform**
- `Attachment`/`Media`, `Tag`, `Feature Flag / AppConfig` (incl. mobile force-update/splash *(insight: MobAppConfig/Splash)*), `Report/Abuse` (flag), `Translation` strings.

### 9.2 Key relationships (text ER)
```
User 1─1 Profile 1─* ProfileLocation *─1 Ward  (1 isPrimary, 1 isElectoral)
Ward *─1 Council/LGA *─1 District *─1 Region ;  Ward *─1 Constituency (via WardConstituency, effective-dated)
Profile 1─* Role(Citizen, Representative, AreaOfficial, …)  // additive on one account (§6.4)
Representative *─1 Constituency|Ward (nullable for special-seats/nominated) *─1 PoliticalParty *─1 Parliament|ZanzibarHoR
Representative 1─* Contribution/Promise/Attendance
RoleAssignment (AREA_OFFICIAL) *─* Area(any level) and *─* IssueCategory
Report *─1 IssueCategory ;  Report *─1 (admin area) ;  Report *─1 assignedOffice ;  Report 1─* CaseEvent
Petition 1─* Signature(by T3 Profile) ;  Petition *─1 target(Representative|Office)
Survey 1─* Question 1─* Option ;  Survey 1─* Response(by Profile)
Project *─* Area ;  Project *─1 responsible(Office|Representative) ;  Project 1─* Milestone/Update
Announcement *─1 author ;  Announcement → audienceScope(geo+role) → FeedItem/Notification per recipient
Follow: Profile *─* (Area|Representative|Category|Project)
```

---

## 10. Functional Requirements — Epics, User Stories & Acceptance Criteria

> Format: **Epic → Stories**. Each story: *As a [actor], I want [capability], so that [value]* + **AC** (acceptance criteria). IDs (US-x.y) are referenced by use cases in §11. Priorities: **MVP**, **P2**, **P3**.

### Epic M0 — Identity, Onboarding & Verification
- **US-0.1 (MVP)** *As a Guest, I want to sign up with my phone (or email) and an OTP, so that I can become a registered citizen.*
  **AC:** OTP via SMS/email; expires (~5 min); max attempts + lockout; account created at **T1**; **one account per phone** (existing phone → offer login/recovery, not a second account); Swahili/English.
- **US-0.2 (MVP)** *As a Citizen, I want to complete my profile with a **single primary residence** at signup, so that I can reach T2 quickly and file reports.* *(Option A — keep signup fast; more locations added later via US-0.8.)*
  **AC:** required fields validated; pick **one** location (GPS or ward drill-down, min = Ward) set as `isPrimary` (and default `isElectoral`); the place resolves admin chain + constituency automatically; reach **T2** when email/phone verified + profile complete; civic-readiness indicator shown *(insight)*.
- **US-0.3 (MVP)** *As a Citizen, I want to verify my national/voter ID, so that I unlock high-trust actions (T3).*
  **AC:** capture idType + idNo (+ optional document/selfie); **dedup — block if the ID already belongs to another account**; auto-verify via provider when available, else queue for Moderator; T2→T3 on success; **voter-ID verification sets the `isElectoral` location authoritatively**; failures explained; PII encrypted.
- **US-0.4 (MVP)** *As a Citizen, I want to log in and recover access, so that I keep my account.* **AC:** login by phone/email + password or OTP; password reset; refresh tokens; optional MFA.
- **US-0.5 (P2)** *As an Organisation, I want to register and get verified, so that we can run campaigns and petitions.* **AC:** ORGANIZATION profile; registration doc upload; Moderator approval; org admin can invite members.
- **US-0.6 (MVP)** *As an Admin, I want to grant the Representative/Official role to a person’s **existing account** (not a new one), so that an elected citizen keeps one identity.* **AC:** find existing account (or invite); verify claim (election results/official list); attach role + scope (constituency/ward or areas/categories) to the same account; role status lifecycle (PENDING→SITTING→FORMER); deactivate role without deleting the account/profile.
- **US-0.7 (MVP)** *As any user, I want to manage notification/language/privacy preferences (incl. **per-location** notifications), so that the app fits me.* **AC:** per-channel + per-location toggles; language; quiet hours; data-sharing consent.
- **US-0.8 (MVP)** *As a Citizen, I want to add and manage multiple locations (home, work, family) and choose which is **primary** and which is **electoral**, so that I engage with all the places I care about.* **AC:** add/remove `ProfileLocation` with `associationType`; exactly one `isPrimary`; exactly one `isElectoral` (voter-ID-authoritative; **manual change rate-limited + audited**); each resolves admin chain + constituency; locations are private.
- **US-0.9 (P2)** *As a multi-role user, I want to switch context (act “as MP” vs “as citizen”), so that my actions are attributed correctly.* **AC:** context switcher; active context recorded on each action; conflict guardrails enforced (can’t act on self/own work — §6.4).

### Epic M1 — Geography & Reference Data
- **US-1.1 (MVP)** *As an Admin, I want to manage the geography hierarchy, so that everything can be geo-scoped.* **AC:** CRUD Region→Hamlet + Constituency; codes auto-generated (sequence-safe); parent integrity; bulk import; soft-delete with referential safety; search/paginate.
- **US-1.2 (MVP)** *As an Admin, I want to manage parties, parliaments, parliament roles, and categories/SLAs.* **AC:** CRUD + status; SLA per category; routing rules per category × area level.
- **US-1.3 (MVP)** *As any user, I want to browse/search locations and pick mine.* **AC:** typeahead; hierarchical drill-down; GPS reverse-geocode to admin area.

### Epic M2 — Representatives & Institutions
- **US-2.1 (MVP)** *As a Citizen/Guest, I want to find my representatives by my location, so that I know who represents me.* **AC:** “Find my rep” by GPS or chosen constituency/ward; shows MP (and councillors later), party, contact, photo, term.
- **US-2.2 (MVP)** *As a Citizen, I want to view a representative profile, so that I can learn their record.* **AC:** bio, constituency, party, parliament role, links to contributions/projects/promises/ratings (as those modules ship), follow button.
- **US-2.3 (P2)** *As a Representative, I want to manage my public profile, so that constituents see accurate info.* **AC:** edit bio/photo/contacts (moderated); cannot change verified identity fields; changes audited.
- **US-2.4 (MVP)** *As an Admin, I want to manage representatives and link them to constituency/party/parliament.* **AC:** real FKs; one sitting MP per constituency at a time; history of former reps retained.

### Epic M3 — Issue Reporting & Case Management
- **US-3.1 (MVP)** *As a Citizen (T2), I want to file a report with category, description, photos, and location, so that the right authority is informed.* **AC:** ≤ 2-min flow; category picker; GPS/manual location; ≤ N attachments with size/type limits + virus scan; offline draft + later sync; ticket code issued; auto-routed to responsible office by category × area.
- **US-3.2 (MVP)** *As a Citizen, I want to track my report’s status and get updates, so that I know it’s handled.* **AC:** timeline of events; push/SMS on status change; ability to add info/comment; reopen if unresolved.
- **US-3.3 (MVP)** *As an Area Official, I want a queue of reports for my scope, so that I can triage and act.* **AC:** filtered to assigned areas/categories; sort/filter by status/priority/age/SLA; assign to self/colleague; SLA timers visible.
- **US-3.4 (MVP)** *As an Area Official, I want to change status, add internal/public notes, request info, and resolve, so that I can manage cases.* **AC:** state machine enforced (§12.1); public vs internal notes; resolution requires note (+ optional proof); citizen notified.
- **US-3.5 (MVP)** *As a Citizen, I want to confirm or dispute a resolution, so that closure reflects reality.* **AC:** on “Resolved”, citizen can **Confirm** (→ Closed) or **Dispute** (→ Reopened/Escalated) within a window; auto-close after timeout.
- **US-3.6 (P2)** *As the System, I want to escalate overdue cases, so that SLAs are honoured.* **AC:** SLA breach → escalate to supervisor/higher area level; notify; escalation logged.
- **US-3.7 (P2)** *As any user, I want to see public reports near me and upvote/follow, so that common problems gain weight.* **AC:** map/list of PUBLIC reports by area; upvote (T1+); follow; duplicates merged by official.
- **US-3.8 (P2)** *As a Moderator, I want to detect and merge duplicates and hide abusive reports.* **AC:** duplicate linking; hide/remove with reason; reporter notified; audit.
- **US-3.9 (P2, channel)** *As a feature-phone Citizen, I want to file/track a report via USSD/SMS.* **AC:** USSD menu (category → area → free-text/voice-to-text optional → confirm); SMS status updates; ticket code by SMS.

### Epic M4 — Announcements & Feed
- **US-4.1 (MVP)** *As an Area Official/Representative, I want to publish a geo-targeted announcement, so that constituents are informed.* **AC:** audience by area scope + optional role/category; schedule publish/expire; channels FEED/PUSH/SMS; moderation for new authors; preview.
- **US-4.2 (MVP)** *As a Citizen, I want a personalised feed (my areas + followed reps + categories), so that relevant info finds me.* **AC:** ranked feed; filters; mark read; share link; offline cache.
- **US-4.3 (MVP)** *As a Citizen, I want to follow/subscribe to areas, representatives, categories, projects.* **AC:** follow/unfollow; feed + notifications reflect subscriptions.

### Epic M5 — Notifications
- **US-5.1 (MVP)** *As a Citizen, I want push/SMS/email for things I care about, respecting my preferences.* **AC:** per-type/channel prefs; quiet hours; language; delivery status; unsubscribe; SMS fallback when no push token.
- **US-5.2 (MVP)** *As the System, I want reliable delivery with retries and dedupe.* **AC:** queued, retried with backoff; idempotent; rate-limited; failures observable.

### Epic M6 — Representative Accountability (P2)
- **US-6.1** *As a Citizen, I want to see a representative’s contributions (speeches, motions, bills, votes), so that I can judge their work.* **AC:** list/filter by type/date/session; source links; created by authorised authors/Admin; citizens read-only.
- **US-6.2** *As a Citizen (T3), I want to rate my representative periodically, so that accountability is visible.* **AC:** one rating per period per rep; aggregate score; abuse-resistant; rep cannot edit/delete ratings; moderation of comments.
- **US-6.3** *As a Citizen, I want to track promises and their status.* **AC:** promise list with status; evidence; link to projects.

### Epic M7 — Projects (P2)
- **US-7.1** *As a Citizen, I want to see development projects in my area with budget, status, and progress.* **AC:** filter by area/sector/status; progress + milestones + updates + photos; follow.
- **US-7.2** *As an Area Official/Representative, I want to publish and update projects.* **AC:** CRUD with FKs to areas; status/progress updates timeline; authorised authors only.
- **US-7.3** *As a Citizen, I want to give feedback/report an issue on a project.* **AC:** link a Report to a Project; comment (moderated).

### Epic M8 — Surveys & Polls (P2)
- **US-8.1** *As an Authority/Representative/Org, I want to create a survey/poll targeted to an audience.* **AC:** questions (single/multi/scale/text); audience by geo/role; anonymity setting; schedule; results visibility rules.
- **US-8.2** *As a Citizen, I want to respond to surveys/polls relevant to me.* **AC:** eligibility by tier/geo; one response (binding polls require T3); see aggregate results if permitted.

### Epic M9 — Petitions (P2)
- **US-9.1** *As a Citizen/Org, I want to create a petition to a representative/authority with a goal and deadline.* **AC:** moderation before public; target; threshold; share.
- **US-9.2** *As a Citizen (T3), I want to sign a petition once.* **AC:** unique signature; count; optional comment; privacy choice.
- **US-9.3** *As a Representative/Authority, I want to respond to a petition that reaches threshold.* **AC:** response published; status SUCCEEDED/RESPONDED; signers notified.

### Epic M10 — Q&A (P2)
- **US-10.1** *As a Citizen (T2), I want to ask my representative a public question.* **AC:** moderated; rate-limited; upvotes prioritise.
- **US-10.2** *As a Representative, I want to answer/decline questions.* **AC:** answer published; decline with reason; askers notified.

### Epic M11 — Discussions & Comments (P2/3)
- **US-11.1** *As a Citizen (T2), I want to comment on announcements/projects/public reports.* **AC:** threaded; moderation; report/flag; edit window.

### Epic M12 — Moderation, Trust & Safety
- **US-12.1 (MVP)** *As any user, I want to flag content/abuse.* **AC:** flag reasons; throttling; feedback.
- **US-12.2 (MVP)** *As a Moderator, I want a queue of flagged items and verification requests.* **AC:** prioritised queue; actions (approve, hide, remove, warn, suspend, verify); reason + audit; appeals.
- **US-12.3 (P2)** *As a Moderator, I want automated assists (profanity, PII, spam, image safety).* **AC:** auto-flag/hold; human-in-the-loop; configurable thresholds; language-aware (Swahili).

### Epic M14 — Admin Console & Configuration
- **US-14.1 (MVP)** *As an Admin, I want dashboards and management for all reference data, users/roles, categories/SLAs, content, and config.* **AC:** RBAC-guarded; audit; bulk ops; feature flags + mobile app config (min version/force-update) *(insight)*.

### Epic M15 — Analytics & Reporting (P2)
- **US-15.1** *As an Admin/Authority, I want operational dashboards (reports volume, TTR, SLA breaches, engagement, geography heatmaps).* **AC:** filterable by area/category/time; export; see Appendix C.

### Epic M16 — Organisations & Campaigns (P3)
- **US-16.1** *As an Organisation, I want to run campaigns aggregating issues/petitions/surveys.* **AC:** org workspace; member roles; campaign analytics.

---

## 11. Use-Case Catalogue (exhaustive) + detailed flows

### 11.1 Complete use-case list (by module)
> Every UC maps to one or more user stories. This catalogue is intended to be exhaustive for v1–v3; detailed flows for the highest-complexity UCs follow in §11.2.

**Identity & Access (UC-A)**
- UC-A01 Sign up (phone) · UC-A02 Sign up (email) · UC-A03 Verify OTP · UC-A04 Complete profile (set **primary residence**) · UC-A05 Verify email · UC-A06 Verify phone · UC-A07 Verify national/voter ID (auto) · UC-A08 Verify ID (operator-assisted) · UC-A09 Log in (password) · UC-A10 Log in (OTP) · UC-A11 Refresh token · UC-A12 Log out · UC-A13 Reset/Change password · UC-A14 Enable MFA · UC-A15 Manage preferences/language (+ per-location) · UC-A16 Manage privacy/consent · UC-A17 Deactivate/delete account (right to erasure) · UC-A18 Register organisation · UC-A19 Verify organisation · UC-A20 Invite/manage org members · UC-A21 Admin onboard Area Official (scope) · UC-A22 Admin onboard Representative (verify claim) · UC-A23 Grant/revoke roles · UC-A24 Suspend/reinstate account · **UC-A25 Add/remove a location · UC-A26 Set primary location · UC-A27 Set electoral location (voter-ID-authoritative, cooldown) · UC-A28 Grant Representative role to existing account (citizen→MP, retains identity) · UC-A29 Detect/merge duplicate account (ID dedup) · UC-A30 Switch active role/context · UC-A31 End representative term (→ FORMER, account persists)**.

**Geography & Reference (UC-B)**
- UC-B01..B07 CRUD Region/District/Constituency/Ward/Village/Hamlet · UC-B08 Bulk import geography · UC-B09 Browse/search locations · UC-B10 Reverse-geocode GPS→area · UC-B11 CRUD Party · UC-B12 CRUD Parliament · UC-B13 CRUD Parliament role · UC-B14 CRUD Issue category · UC-B15 Configure SLA & routing rules.

**Representatives & Institutions (UC-C)**
- UC-C01 Find my representatives · UC-C02 View representative profile · UC-C03 Follow/unfollow representative · UC-C04 Admin create/link representative · UC-C05 Representative edits own profile (moderated) · UC-C06 List/search representatives · UC-C07 View party/parliament directory · UC-C08 Transition representative SITTING→FORMER (term end).

**Reporting & Cases (UC-D)**
- UC-D01 File report (app) · UC-D02 File report (USSD/SMS) · UC-D03 Save offline draft & sync · UC-D04 Auto-route report · UC-D05 Track report/timeline · UC-D06 Add info/comment to own report · UC-D07 Official triage queue · UC-D08 Assign/reassign case · UC-D09 Change status / add note · UC-D10 Request more info · UC-D11 Resolve case · UC-D12 Citizen confirm/dispute resolution · UC-D13 Auto-close after timeout · UC-D14 Escalate (SLA breach/manual) · UC-D15 Merge duplicates · UC-D16 Upvote/follow public report · UC-D17 Hide/remove abusive report · UC-D18 Reopen case · UC-D19 Withdraw own report · UC-D20 Export/case report.

**Engagement (UC-E)**
- UC-E01 Create petition · UC-E02 Moderate petition · UC-E03 Sign petition · UC-E04 Petition threshold reached → notify target · UC-E05 Respond to petition · UC-E06 Create survey/poll · UC-E07 Respond to survey/poll · UC-E08 View results · UC-E09 Ask question (Q&A) · UC-E10 Answer/decline question · UC-E11 Upvote question · UC-E12 Comment on entity · UC-E13 Flag content · UC-E14 Edit/delete own comment.

**Accountability & Activities (UC-F)**
- UC-F01 Publish representative contribution · UC-F02 View contributions · UC-F03 Record attendance · UC-F04 Create/track promise · UC-F05 Rate representative · UC-F06 View aggregate ratings · UC-F07 Publish project · UC-F08 Update project status/progress · UC-F09 View projects by area · UC-F10 Follow project · UC-F11 Link report to project.

**Communications (UC-G)**
- UC-G01 Publish announcement · UC-G02 Schedule/expire announcement · UC-G03 Moderate announcement (new author) · UC-G04 View personalised feed · UC-G05 Follow area/category · UC-G06 Receive push · UC-G07 Receive SMS · UC-G08 Manage notification preferences · UC-G09 Mark notification read · UC-G10 Digest (daily/weekly).

**Moderation & Admin (UC-H)**
- UC-H01 Review flag queue · UC-H02 Take moderation action · UC-H03 Handle appeal · UC-H04 Review verification queue · UC-H05 Auto-moderation triage · UC-H06 Admin dashboards · UC-H07 Configure feature flags/app config · UC-H08 View audit log · UC-H09 Manage taxonomies · UC-H10 Bulk operations.

**Platform/System (UC-S)**
- UC-S01 SLA timer tick & escalation · UC-S02 Feed materialisation · UC-S03 Notification dispatch/retry · UC-S04 ID verification callback · UC-S05 Search indexing · UC-S06 Media virus scan · UC-S07 Analytics event capture · UC-S08 Scheduled digests · UC-S09 Data export/erasure job · UC-S10 Reference-data sync/import.

### 11.2 Detailed flows (high-complexity use cases)

**UC-D01 — File a report (mobile app)**
- **Actor:** Citizen (T2+). **Goal:** Submit a routed, tracked report. **Preconditions:** authenticated, T2; online or offline.
- **Main flow:**
  1. Citizen taps “Report an issue”.
  2. Selects category (hierarchical; recent/popular surfaced).
  3. Adds title + description (voice-to-text optional); attaches ≤N photos/docs.
  4. Sets location: GPS (default) or manual area pick; system reverse-geocodes to admin area + constituency.
  5. Chooses visibility (PUBLIC default / PRIVATE).
  6. Reviews summary → Submit.
  7. System validates, scans attachments, assigns ticket code, determines responsible office by **category × area routing rules**, creates Report (status NEW) + initial CaseEvent, notifies the office, and confirms to citizen with code + expected first-response SLA.
- **Alternate/exception flows:** offline → store draft, sync when connected (UC-D03); attachment too large/unsafe → reject with guidance; no routing match → route to default area office + flag for Admin; possible duplicate detected → suggest existing report to follow instead.
- **Postconditions:** Report persisted; reporter can track (UC-D05); SLA clock started.

**UC-D04 — Auto-route report (System)**
- Determine responsible office from `(category, adminArea level)` routing config; pick the Area Official scope that matches the smallest covering area + category; if multiple, round-robin/load-balance; if none, default + Admin alert. Set `dueAt = now + category.SLA`.

**UC-D11/12/13 — Resolve → Confirm/Dispute → Close**
- Official sets RESOLVED with resolution note (+optional proof) → citizen notified → citizen **Confirms** (→ CLOSED) or **Disputes** (→ REOPENED, re-enters queue, optional escalation) within window W; if no response in W → auto-CLOSED (system), citizen still able to reopen for period R.

**UC-A07/08 — ID verification (auto + assisted)**
- Citizen submits idType+idNo (+document/selfie). System calls Identity Verification Provider (SY3). On success → Profile.idVerified=true, trustTier→T3. On provider unavailable/ambiguous → create VerificationRequest → Moderator reviews evidence (UC-H04) → approve/reject with reason. All PII encrypted; minimal retention; audit.

**UC-E03 — Sign petition**
- Precondition: T3. System enforces one signature per profile; increments count atomically; on reaching `signatureGoal` triggers UC-E04 (notify target, status SUCCEEDED). Signer privacy honoured.

**UC-G01/04 — Publish announcement → personalised feed**
- Author composes; selects audience scope (areas + optional role/category) and channels; new/untrusted authors → moderation hold (UC-G03). On publish, system fans out to matching subscribers’ feeds (UC-S02) and queues notifications per preference (UC-S03) in the recipient’s language.

**UC-D02/D09 — USSD/SMS report & status**
- Inbound USSD session: language → category → area (or “use my registered area”) → description (concatenated SMS or guided) → confirm → ticket code returned by SMS. Status changes pushed as SMS. Account auto-linked by MSISDN (creates T1 if new).

**UC-A28 — Citizen elected → Representative role on the same account**
- **Actor:** Admin (grantor); subject = an existing Citizen account. **Preconditions:** subject has a (verified) Profile; official election result available.
- **Main flow:** Admin searches the subject’s **existing account** (by name/phone/ID) → verifies the claim against the official MP/councillor list → attaches a `Representative` record (type/mandate, constituency or ward, party, parliament/legislature, term) with status `SITTING` and grants the `REPRESENTATIVE` role to the **same `User`** → subject is notified.
- **Result:** subject keeps one login, one profile, the Citizen role, and all prior history; gains MP capabilities (post updates, answer Q&A, respond to petitions). The conflict guardrails (§6.4/D16) prevent them rating/petitioning **themselves**.
- **Alternate flows:** no existing account → invite to create one first (never create a duplicate); claim unverifiable → reject; special-seats/nominated → `mandate ≠ CONSTITUENCY`, constituency FK null.
- **Term end (UC-A31):** Representative → `FORMER` (record + history retained); role capabilities revoked; **account + Citizen role persist**; re-election re-activates on the same account.

**UC-A25/26/27 — Manage locations (add / set primary / set electoral)**
- Add a `ProfileLocation` (pick ward/village or GPS; choose `associationType`). Setting **primary** moves the single `isPrimary` flag (affects default feed/report area). Setting **electoral** is constrained: defaults to primary, set **authoritatively on voter-ID verification**; manual change is **rate-limited (cooldown)** + audited (anti-gaming). Binding civic actions re-scope to the new electoral location only after the change settles. Locations are private; never shown publicly.

---

## 12. Status Workflows & State Machines

### 12.1 Report/Case lifecycle
```
NEW ──assign──► ASSIGNED ──start──► IN_PROGRESS ──need info──► AWAITING_INFO ──reply──► IN_PROGRESS
  │                                  │
  │                                  ├─resolve──► RESOLVED ──confirm──► CLOSED
  │                                  │                  └─dispute──► REOPENED ──► ASSIGNED
  ├─reject(invalid/dup)──► REJECTED/DUPLICATE
  └─(SLA breach at any active state)──► ESCALATED (stays active, supervisor notified)
REOPENED/RESOLVED ──auto-timeout──► CLOSED
```
Allowed transitions, who may perform them, and notifications are enforced server-side and audited.

### 12.2 Other lifecycles
- **Petition:** DRAFT → (moderation) → ACTIVE → SUCCEEDED → RESPONDED → CLOSED (or CLOSED on deadline).
- **Survey/Poll:** DRAFT → SCHEDULED → OPEN → CLOSED → (results) ARCHIVED.
- **Question:** OPEN → ANSWERED | DECLINED | MODERATED.
- **Verification:** PENDING → APPROVED | REJECTED | MORE_INFO.
- **Account:** PENDING → ACTIVE → SUSPENDED → DISABLED; trustTier T0→T1→T2→T3 (and downgrade on revocation).
- **Representative:** PENDING_VERIFICATION → SITTING → FORMER.
- **Project:** PLANNED → ONGOING → (STALLED) → COMPLETED.
- **Announcement:** DRAFT → (moderation) → SCHEDULED → PUBLISHED → EXPIRED.

---

## 13. Notifications & Channels Matrix

| Event | Recipient | Default channels | Tier/Pref |
|---|---|---|---|
| OTP / verification | self | SMS/Email | always |
| Report received (ack) | reporter | Push, SMS | pref |
| Report status change | reporter + followers | Push, SMS, Feed | pref |
| Case assigned/escalated | official/supervisor | Admin, Email, Push | role |
| SLA breach imminent/over | official + supervisor | Admin, Email | role |
| New announcement in my area | subscribers | Push, Feed, (SMS optional) | pref |
| Petition reached threshold | target + signers | Push, Feed, Email | pref |
| Q&A answered | asker + upvoters | Push, Feed | pref |
| Survey/poll invitation | targeted audience | Push, Feed, SMS | pref |
| Rating period open | T3 citizens | Push, Feed | pref |
| Moderation outcome/appeal | content owner | Push, Email | always |
| Project update I follow | followers | Push, Feed | pref |

Rules: respect per-user **NotificationPreference** (channel/type, quiet hours, language); SMS used as fallback when no push token; all sends idempotent, rate-limited, retried, and logged with delivery status.

---

## 14. Clients & Channels

- **Mobile app (Flutter)** — primary citizen client; offline drafts, push (FCM), GPS, camera, low-data mode, Swahili/English, force-update via app config *(insight)*.
- **Web / PWA (responsive)** — citizens, representatives, organisations; installable; SEO for public representative/project/announcement pages.
- **Admin console (Angular)** — authorities, moderators, admins; dense data tables, queues, dashboards, RBAC-scoped.
- **USSD/SMS** — feature-phone reporting, status, alerts, OTP; aggregator integration; account linked by MSISDN.
- **Public read API / embeds** (P3) — open civic data (representatives, projects, aggregate stats).

---

## 15. Non-Functional Requirements

**Performance & scale:** API p95 < 500ms for reads, < 1s for writes under expected load; feed and search optimised (indexes, caching); handle bursts (e.g. mass announcement) via async fan-out. Target national scale (millions of citizens) with horizontal scalability.

**Availability & resilience:** 99.9% monthly for core APIs; graceful degradation (feed/search read-only if a dependency is down); retries/circuit-breakers for SMS/verification providers; no single hard dependency on the mobile path.

**Security:** see §18. OWASP ASVS-aligned; all admin/official actions authorised + audited.

**Privacy & compliance:** Tanzania **Personal Data Protection Act (2022/2023)** alignment; data minimisation; PII encryption at rest (esp. national IDs); consent management; right to access/erasure (UC-A17/UC-S09); configurable data retention.

**Accessibility:** WCAG 2.1 AA for web/admin; large-touch, high-contrast, screen-reader labels on mobile; low-literacy-friendly flows (icons, voice input, simple Swahili).

**Internationalisation:** all user-facing strings externalised; Swahili default + English; locale-aware dates/numbers; right-to-grow to more languages.

**Observability:** structured logs (trace/span ids), metrics (`/metrics`), tracing (OpenTelemetry), health (`/health`,`/live`), dashboards & alerts (5xx, latency, SLA breaches, queue depth) per `deploy.md` north-star *(insight)*.

**Maintainability/quality:** modular boundaries; ≥80% test coverage on core modules; contract tests for clients; CI gates (lint, test, SAST, container scan); Flyway migrations; **no committed secrets** *(insight: prior repos hardcoded secrets/passwords)*.

**Cost/data efficiency:** image compression/thumbnails, pagination, delta sync, SMS used sparingly.

**Auditability:** immutable audit log for all state-changing official/admin/moderation actions.

---

## 16. System Architecture

**Style:** **Modular monolith** (Spring Boot, Java 21) with strict module boundaries (identity, geography, institutions, reporting, engagement, accountability, communications, moderation, admin, notifications), designed to extract high-load modules (notifications, feed, search, reporting) into services later. Avoids premature microservices while keeping seams clean. *(Improves on the prior copy-paste modules by introducing a shared kernel: base entity, response envelope, error model, pagination, audit, code generation.)*

**Key components**
- **API gateway / app**: REST + OpenAPI; versioned (`/api/v1`); stateless JWT (access + rotating refresh); RBAC method security on every endpoint.
- **PostgreSQL**: primary store (PostGIS optional for geo); Flyway migrations; `ddl-auto=validate`.
- **Redis**: caching, rate limiting, OTP store, idempotency keys, ephemeral session/USSD state.
- **Object storage (S3-compatible)**: attachments/media; signed URLs; virus scan on upload.
- **Event bus** (e.g. Kafka/RabbitMQ or transactional outbox to start): domain events for feed fan-out, notifications, SLA, analytics, search indexing.
- **Notification service** (SY1): push (FCM), SMS (aggregator), email; preference-aware, retried.
- **USSD/SMS gateway adapter** (SY2): inbound sessions + outbound.
- **Identity verification adapter** (SY3): NIDA/voter + phone; pluggable, with operator-assisted fallback.
- **Search** (SY5): Postgres FTS to start; OpenSearch/Elasticsearch when needed.
- **Scheduler/workflow** (SY4): SLA clocks, escalations, digests (Quartz/Spring scheduling → durable workflow if needed).

**Text architecture diagram**
```
[Flutter app]  [Web/PWA]  [Angular admin]      [USSD/SMS]
      \           |            /                   |
       \          |           /              [SMS/USSD Gateway]
        ▼         ▼          ▼                      ▼
                [ API (Spring Boot, modular) ]──emits──► [Event Bus]
                  │     │       │      │                   │  │  │
              [Postgres][Redis][Object store][Search]      │  │  └► [Analytics pipeline]
                                                           │  └► [Notification svc]→FCM/SMS/Email
                                                           └► [Scheduler/SLA/Feed/Index workers]
                          [Identity Verification Provider (NIDA/Voter)]  [Auth: JWT]
```

**Environments & delivery:** dev/staging/prod; containerised; CI/CD with tests, SAST, container scan; blue/green or rolling; migrations gated; observability + one-command rollback. Start lean (managed VM + systemd) and grow to orchestrated containers *(insight: README systemd vs deploy.md K8s)*.

---

## 17. API Design Principles

- **REST + OpenAPI** (Swagger UI), **versioned** (`/api/v1`), resource-oriented, plural nouns.
- **Public ids = UUID/ULID** in URLs (never sequential DB ids); human codes (e.g. ticket `TAR-YYYY-NNNNNN`) for display.
- **One consistent response envelope** for all responses incl. errors and pagination *(insight: prior systems had 3 inconsistent envelopes)*:
  ```json
  { "success": true, "code": "OK", "message": "...", "data": { }, "meta": { "page": 0, "size": 20, "total": 137 }, "timestamp": "..." }
  ```
- **Errors**: stable machine codes + localised messages + field-level validation details; correct HTTP status.
- **Pagination/sort/filter**: standard params (`page,size,sort,q,filters`); consistent defaults.
- **Idempotency** keys for create/submit (reports, signatures, OTP); **optimistic concurrency** on updates.
- **Auth**: bearer JWT, short-lived access + rotating refresh; tiered authorization claims verified server-side; no privileged action trusts the client.
- **Rate limiting** & abuse protection on public/auth/report/comment endpoints.
- **Backward compatibility** policy; deprecations announced; contract tests with clients.

---

## 18. Security, Privacy & Compliance

- **AuthN:** phone/email + password or OTP; rotating refresh tokens; optional MFA for staff; lockout/backoff; **no hardcoded/seed passwords in source**, secrets from env/secret manager *(insight: prior repos shipped hardcoded root passwords and even logged them)*.
- **AuthZ:** RBAC + scope (area/category/constituency), **method-level**, deny-by-default; admin/official/moderator endpoints fully guarded *(insight: fixes prior “authenticated-only” admin and missing `@PreAuthorize` on activity controllers)*.
- **Tiered verification (L3):** progressive trust; high-stakes actions (petitions, ratings, official reports, binding polls) require T3; verification events audited; downgrade on revocation/abuse.
- **Data protection:** PII (esp. national/voter ID) encrypted at rest; field-level encryption for IDs; least-privilege data access; PII redaction in logs; configurable retention; consent + privacy center; **right to access/erasure**.
- **Content safety:** moderation pipeline (flagging, queues, auto-assist for profanity/PII/spam/image safety, Swahili-aware), takedowns + appeals, audit.
- **Transport & platform:** TLS everywhere; tight CORS (allow-list origins, no wildcard-with-credentials) *(insight: prior CORS was `*` + credentials)*; CSRF strategy appropriate to token model; secure headers; dependency & container scanning; secrets rotation.
- **Abuse/integrity:** rate limits, duplicate/sockpuppet detection, signature/rating integrity, anti-automation on OTP and reports.
- **Auditing & transparency:** immutable audit trail; transparency reporting on moderation (P3).

---

## 19. Resolved Decisions (development gate)

All prior open questions have been **decided** (2026-06-22). These are binding for detailed design and development.

| # | Decision | Resolution | Impact |
|---|---|---|---|
| **D-Q1** | **Anonymous reporting** | **Tiered + anonymous for sensitive categories.** T1 users file *community* reports; fully anonymous allowed only for sensitive categories (corruption, GBV) with stricter moderation & rate limits. | Reporting flow has 3 trust modes; moderation rules per category. |
| **D-Q2** | **ID verification (T3)** | **Pluggable adapter; operator-assisted at launch, NIDA later.** Launch with operator-assisted ID review + email/phone OTP; integrate NIDA API when access is granted. No launch dependency on NIDA. | Verification is an interface with 2 implementations; moderator verification queue is MVP. |
| **D-Q3** | **Representative scope** | **MPs + Councillors + ward/village executive officers at launch** (full local government). | Larger onboarding/verification program; `Representative.type` drives variations. |
| **D-Q4** | **Accountability data authorship** | **Curated by platform/partners (Phase 2);** reps may self-add with moderation. | M6/M7 are Phase 2; need curator tooling + partner roles. |
| **D-Q5** | **Report responders** | **Nationwide government-agency onboarding at launch.** Software is launch-ready; **stage real onboarding region-by-region** to prove the loop. | Biggest delivery risk = partnerships, not code; large official-onboarding workstream. |
| **D-Q6** | **Geography source of truth** | **Seed from official government dataset; maintain via admin console + bulk import** for changes. | Need an official seed dataset + import tooling in MVP. |
| **D-Q7** | **USSD/SMS** | **Procure shortcode; SMS (OTP/alerts) early, USSD reporting pilot in Phase 2.** | SMS in MVP notifications; USSD module Phase 2. |
| **D-Q8** | **Moderation model** | **Hybrid:** automated assist (Swahili-aware) + in-house moderators + community flagging. | Moderation pipeline + staffing plan; auto-assist Phase 2. |
| **D-Q9** | **Hosting/residency** | **In-country where feasible; cloud-portable design.** Finalise specifics with legal. | Portable infra; PII/ID data residency reviewed with legal. |
| **D-Q10** | **Languages** | **Swahili (default) + English at launch.** App name = **Taarifu**. | i18n with SW+EN strings from day one. |
| **D11** | **Registration locations** | **Option A:** capture **one primary residence** at signup; add more locations later in profile. | Fast signup; US-0.2 captures a single primary, US-0.8 manages the rest. |
| **D12** | **Multi-location identity** | A profile holds **many locations**; **one `isPrimary`** (default context) + **one `isElectoral`** (binding civic weight). Each pinned place derives the **administrative chain + constituency**. | §9.0 location model; action scoping by location. |
| **D13** | **Electoral integrity** | Binding actions (rate MP, sign constituency petition, binding poll) scoped to the **single electoral location**; `isElectoral` is **voter-ID-authoritative** with a **change cooldown**. | Prevents double-influence across locations. |
| **D14** | **Admin hierarchy** | Add **Council/LGA (Halmashauri)** (and optional **Division/Tarafa**) to Region→…→Hamlet. Ward = minimum pin granularity. | More seed data; routing at Council level. |
| **D15** | **One account per person** | One account per **phone** at signup; **national/voter-ID dedup** (block/merge) at verification. Roles are **additive** on the same account. | Citizen→MP keeps one account/identity (§6.4). |
| **D16** | **Role conflicts** | **Block self-actions** (no rating/petition/answer/resolution/moderation of self or own work); staff may use citizen features; **all multi-hat actions audited**. | Conflict-of-interest guardrails in every affected use case. |
| **D17** | **Zanzibar** | **Mainland-first; Zanzibar (admin structure + House of Representatives) in Phase 2.** `Representative.legislature` + `mandate` already model it. | Phase 2 geography + legislature seed. |
| **D18** | **Token economy** | **Everything metered with free recurring quotas for civic-core actions;** tokens **never** buy democratic weight (integrity fence). | New Wallet/Ledger module (§23); free in MVP. |
| **D19** | **Payments** | **Free tokens in MVP; purchase via mobile money (M-Pesa/Tigo Pesa/Airtel Money) + card in Phase 2.** Reverses the v1 "no payments" non-goal. | Payment adapter + packages (§23). |
| **D20** | **Service-provider responders** | **One generalized Responder directory (govt + parastatal + private), phased onboarding** (utilities first, then banks/telecoms); private = paying B2B tier. | Generalizes Area Official (§24). |
| **D21** | **Multisectoral reports** | **One accountable owner + collaborators, optional split into linked sub-cases**; aggregated status, per-responder visibility. | Multi-responder routing (§24). |

> **Status: all decisions cleared.** This PRD is ready for development approval. Remaining pre-coding work is *design* (OpenAPI, DDL/migrations, wireflows) plus the *delivery program* for the two ambitious tracks — D3 (local-leader onboarding) and D5 (nationwide agency onboarding) — which run as partnership/operations workstreams alongside engineering.

---

## 20. Glossary

- **Taarifu** — Swahili for *report/inform/notify*; the platform name.
- **Constituency (Jimbo)** — electoral division electing an MP; belongs to a District.
- **Council / LGA (Halmashauri)** — Local Government Authority (City/Municipal/Town/District Council); where many services & officials sit; sits between District and Ward.
- **Division (Tarafa)** — administrative level between Council and Ward (optional in the model).
- **Ward (Kata) / Village (Kijiji) / Mtaa / Hamlet (Kitongoji)** — administrative sub-divisions (Mtaa = urban street-level; Kitongoji = sub-village).
- **Administrative vs electoral location** — the *administrative* chain (Region→…→Ward) drives services/officials/councillor & report routing; the *electoral* mapping (Ward→Constituency) drives MP representation. Both derived from one pinned place.
- **Primary vs electoral location** — `isPrimary` = default context; `isElectoral` = the single location with binding civic weight (rating/petition/binding poll).
- **NIDA** — National Identification Authority (Tanzania national ID). **Voter ID** — voter-registration identity (ties a person to a constituency).
- **MP / Mbunge** — Member of Parliament. **Councillor / Diwani** — ward-level council representative.
- **Special seats (Viti Maalum) / Nominated MP** — MPs without a constituency (`mandate` ≠ CONSTITUENCY).
- **Zanzibar House of Representatives** — Zanzibar’s legislature (Wawakilishi), separate from the Union Parliament (Phase 2).
- **CDF** — Constituency Development Fund (development projects).
- **Trust tier (T0–T3)** — progressive verification level gating actions.
- **Area Official** — government officer responsible for an area/department, handling reports.
- **SLA** — service-level target (e.g. time-to-first-response, time-to-resolution).
- **Civic readiness** — measure of how complete/verified a profile is for participation.

---

# Part II - Extended Specification (v1.1)

> Added 2026-06-22. Extends the v1.0 PRD with external integrations, search, the token economy, multi-sector responders, edge-case/gap closures (from the completeness review), risks, rollout & data migration, and end-to-end journeys, plus expanded appendices (D-F). Where these refine a v1.0 section they take precedence.

---

## 21. Dependencies & External Integrations

> Every external system Taarifu touches is integrated through a **pluggable adapter** behind a stable internal port (interface), so providers can be swapped per market/contract without touching domain logic. Each adapter has an explicit **degradation mode** — Taarifu **never hard-fails the citizen path** on a third-party outage (NFR §15: "no single hard dependency on the mobile path"). All outbound calls run through the **event bus / outbox** or a queued worker with retries, backoff, circuit-breakers, idempotency keys, and timeouts; all integration events are audited (§18). This section makes the system actors (SY1–SY7) and architecture seams (§16) concrete and contract-ready.

### 21.1 Integration principles (binding)

- **DI1 — Adapter-per-dependency.** Each external system sits behind a Spring port (e.g. `IdentityVerificationProvider`, `SmsGateway`, `PushSender`, `ObjectStore`, `Geocoder`). Implementations are selected by config/feature-flag; ≥1 **stub/sandbox** implementation exists for dev, test, and demo. *(Direct vendor SDK calls in domain code are forbidden — fixes the prior tight coupling.)*
- **DI2 — Graceful degradation by default.** Every adapter declares a degraded path: **queue, fallback channel, cached value, or operator-assisted manual route**. The product stays usable (possibly read-only or delayed) when a dependency is down.
- **DI3 — Async + durable.** Side-effecting integrations (notifications, indexing, analytics, verification callbacks) go via **transactional outbox → event bus → worker** so a provider outage never rolls back the user's transaction. Retries use exponential backoff + jitter; poison messages go to a dead-letter queue (DLQ) with alerting.
- **DI4 — Idempotency & dedupe.** Outbound sends and inbound callbacks/webhooks carry idempotency/correlation keys (stored in Redis/Postgres) so retries and duplicate provider callbacks are safe.
- **DI5 — Secrets & residency.** All credentials come from env/secret manager (never source — §18); PII-bearing integrations (NIDA/voter, SMS MSISDN) respect **in-country-where-feasible** hosting (D-Q9) and **PDPA 2022/2023** (§15). Data shared with each provider is minimised to the field set in the table below.
- **DI6 — Observability per integration.** Per-adapter metrics (success/error rate, p95 latency, queue depth, circuit state, provider cost/volume), structured logs with correlation ids (PII-redacted), and dashboards/alerts (§15 observability).
- **DI7 — Provider-agnostic contracts.** No constituency/ward/MP semantics leak into a vendor format; mapping happens in the adapter. Aggregators are assumed **multi-provider** (≥2 SMS/USSD routes) for failover and least-cost routing where contracts allow.

### 21.2 Summary table

| # | Dependency | System actor | Purpose | Integration approach | Data exchanged (minimised) | Degradation behaviour | Phase |
|---|---|---|---|---|---|---|---|
| **EI-1** | **NIDA national-ID verification** | SY3 | T3 identity verification; one-account-per-person dedup (D15) | Pluggable `IdentityVerificationProvider`; REST to NIDA API (when access granted); async callback/poll | Out: idType=NATIONAL, idNo, name/DOB to match, optional selfie/doc ref. In: match result, verification token. **No bulk ID dataset stored** | **Queue → operator-assisted review** (Moderator, UC-A08); T3 unlocks on manual approval; **no launch dependency** | MVP=operator-assisted; **NIDA later** (D-Q2) |
| **EI-2** | **Voter-ID verification** | SY3 | T3 + **authoritative `isElectoral`** location (ward→constituency) (D13) | Same port, `IdType.VOTER` impl; partner/registry API or operator-assisted | Out: idNo, names. In: match + **registered ward/constituency** | Operator-assisted fallback; if absent, `isElectoral` stays citizen-set with cooldown (D13) | MVP=operator-assisted; registry API when available |
| **EI-3** | **SMS gateway / aggregator** | SY1/SY2 | OTP, report acks/status, alerts, digests; sender-ID/**shortcode** | `SmsGateway` port; HTTPS submit + **delivery-receipt (DLR) webhook**; long/concat SMS; Swahili (GSM-7/UCS-2) | Out: MSISDN, body, sender-id. In: DLR status, inbound MSISDN+text | **Multi-route failover**; if all down → queue + retry; OTP can fall back to **email**; alerts degrade to **feed/push only** | **MVP** (OTP + alerts); shortcode procured (D-Q7) |
| **EI-4** | **USSD aggregator** | SY2 | Feature-phone report/track/alerts via menu sessions; shortcode | `UssdGateway` port; **session webhook** (CON/END), state in **Redis** keyed by MSISDN+sessionId | In: MSISDN, sessionId, text. Out: menu string. Links account by MSISDN (creates T1) | If USSD down, **SMS keyword fallback** for status; report intake degrades to SMS/app | **Phase 2** pilot (D-Q7) |
| **EI-5** | **Push — FCM (Android) / APNs (iOS)** | SY1 | App push for status, announcements, feed events | `PushSender` port; FCM HTTP v1 + APNs token auth; token registry per device | Out: device token, payload (localised, minimal). In: token validity/unregister | **SMS fallback when no valid push token** (US-5.1); invalid tokens pruned; feed always retains item | **MVP** |
| **EI-6** | **Email / SMTP (+ deliverability)** | SY1 | OTP fallback, verification, staff/official notices, digests, password reset | `EmailSender` port; transactional ESP (SMTP/API) + SPF/DKIM/DMARC; bounce/complaint webhook | Out: email, localised template vars. In: bounce/complaint events | Queue + retry; on hard bounce → mark email unverified, prefer SMS/push; never blocks signup | **MVP** |
| **EI-7** | **Maps / reverse-geocoding** | SY5-adjacent | GPS → ward/village → derive admin chain + constituency (US-1.3, UC-B10) | `Geocoder` port; **internal PostGIS point-in-polygon against seeded boundaries (primary)**; external maps/tiles optional for display | Out: lat/long. In: admin area id (internal). External: tiles/geocode only if internal miss | **Internal boundaries are source of truth**; if external map/tiles down → manual ward drill-down still works | **MVP** (internal); external tiles optional |
| **EI-8** | **Object storage (S3-compatible) + virus scanning** | SY6 | Store report photos/docs, profile/rep media, evidence; signed URLs | `ObjectStore` port (S3 API); **pre-signed upload**; `MalwareScanner` port (e.g. ClamAV/engine) on `quarantine→clean` bucket flow; EXIF/geo strip | Out/In: media bytes, content-type, scan verdict | Unscanned media held in **quarantine** (not served); scanner down → upload accepted, **delivery deferred** until scan clears | **MVP** |
| **EI-9** | **Analytics / event pipeline** | SY7 | KPIs, dashboards, funnels, heatmaps (Appendix C); product + ops telemetry | Domain events → bus → sink (warehouse/OLAP or analytics service); **fully async, best-effort** | Out: pseudonymised event stream (no raw PII; ids hashed) | **Never on critical path**; buffer/drop-with-counter if sink down; backfill from event log | MVP=core events; **full analytics Phase 2** (M15) |
| **EI-10** | **Search index** | SY5 | Full-text + geo search over public entities | `SearchPort`; **Postgres FTS at launch**, OpenSearch/Elasticsearch later; index via events (UC-S05) | Out: indexable public docs (reps, projects, announcements, public reports) | Index lag tolerated; if external index down → **fall back to Postgres FTS / DB query** | MVP=Postgres FTS; external engine when scale demands |
| **EI-11** | **Parliament / Hansard official data** | partner/curator | Representative contributions, attendance, sessions (M6) | **Curated import** (admin/partner tooling) + scrape/feed where lawful; **not authoritative source of truth** | In: contributions, votes, attendance, session metadata, source URLs | Manual curation continues if no feed; data shown with provenance/`sourceUrl` | **Phase 2** (D-Q4) |
| **EI-12** | **Election results / official rep lists** | partner/curator | Verify rep claims; seat→person mapping (UC-A22/A28); term changes | Curated import + Admin/Moderator verification against **official MP/councillor list** | In: official winners per constituency/ward, party, term | Onboarding proceeds via manual verification; no live feed required | **MVP** (manual list) → feed Phase 2 |
| **EI-13** | **CDF / development-project data** | partner/curator | Projects, budgets, status, progress per area (M7) | Curated import + authorised author entry; FKs to areas (D-Q4) | In: project name, sector, fundingSource=CDF, budget, status, area refs | Authorised manual entry is the baseline; bulk import optional | **Phase 2** (D-Q4) |
| **EI-14** | **Geography seed dataset (official)** | reference | Source of truth for Region→Hamlet + Constituency + Ward↔Constituency (D-Q6, D14) | One-time **seed + bulk import** via admin console; effective-dated `WardConstituency` | In: official admin boundaries, codes, ward-constituency mapping | Static once seeded; re-delimitation handled by effective-dated import (no live dependency) | **MVP** (D-Q6) |
| **EI-15** | **Identity provider / SSO for staff** | SY3-adjacent | Optional staff (Admin/Moderator/Official) SSO + MFA | OIDC adapter (optional); else native auth + MFA (§18) | Out/In: OIDC tokens, group→role claims | Falls back to native staff login + TOTP MFA | Optional / Phase 2 |
| **EI-16** | **App distribution + force-update** | platform | Play/App Store delivery; min-version/force-update gate | Store pipelines; **AppConfig** (min version, splash, flags) served by API *(insight: MobAppConfig/Splash)* | Out: version policy, flags | App enforces min-version locally; store outage doesn't affect running clients | **MVP** |
| **EI-17** | **Voice-to-text (optional)** | optional | Voice input for low-literacy reporting/USSD (US-3.1, UC-D01) | `SpeechToText` port; Swahili-capable engine; off by default | Out: audio. In: transcript | Pure enhancement; absent → text/photo entry only | Phase 2/3 (optional) |
| **EI-18** | **Auto-moderation / content-safety** | SY-mod | Profanity/PII/spam/image-safety assist, **Swahili-aware** (US-12.3, D-Q8) | `ContentSafety` port; ML service or hosted API; hold-for-review | Out: text/image. In: risk scores/labels | If unavailable → **all content routed to human moderators** (hybrid model, D-Q8) | MVP=basic/manual; **auto-assist Phase 2** |
| **EI-19** | **Secrets manager / KMS** | platform | Secret storage; **field-level encryption keys** for national/voter IDs (§18) | Vault/cloud KMS; envelope encryption for PII columns | Out/In: encrypted keys | Cached lease; on KMS outage, no new decryptions but service stays up for non-PII paths | **MVP** |

### 21.3 Per-integration notes

**EI-1 NIDA national-ID (SY3).** The flagship T3 verifier and the **dedup authority** for one-account-per-person (D15). Behind the `IdentityVerificationProvider` port with two implementations: **operator-assisted** (Moderator reviews idNo + optional document/selfie via the verification queue, UC-H04) at launch, and the **NIDA API** when access is granted (D-Q2). Verification is **async**: submit → `VerificationRequest(PENDING)` → provider callback or moderator decision → T2→T3 and `idVerified=true`, or `REJECTED/MORE_INFO`. ID fields are **field-level encrypted** (EI-19); only a match verdict and minimal proof are retained (data minimisation, §18). **Dedup** runs on the (idType,idNo) hash at verification: a duplicate is **blocked or routed to merge** (UC-A29). *Degradation:* NIDA down/ambiguous → silently fall to the operator queue; the citizen sees "verification pending", and **no T1/T2 capability is lost** while waiting.

**EI-2 Voter-ID (SY3).** Distinct from NIDA because it carries **electoral weight**: a successful voter-ID match sets `isElectoral` **authoritatively** to the registered ward→constituency (D13), overriding citizen-set values and bypassing the manual-change cooldown. Same port, `IdType.VOTER` implementation; registry/partner API where available, else operator-assisted. *Degradation:* without a voter-ID source, `isElectoral` defaults to `isPrimary` and is citizen-changeable only under the audited cooldown (D13) — binding actions (rate MP, sign constituency petition, binding poll) remain correctly single-scoped.

**EI-3 SMS gateway/aggregator (SY1/SY2).** Carries **OTP** (signup/login/MFA fallback), report acknowledgements/status, area announcements (when SMS channel selected), survey invites, and digests — over a **procured shortcode / registered sender-ID** (D-Q7). `SmsGateway` port supports **multi-provider least-cost routing + failover**, long/concatenated and **UCS-2 for full Swahili**, and a **delivery-receipt (DLR) webhook** updating `Notification.status {SENT→DELIVERED/FAILED}`. Inbound SMS (keywords, status checks) links to accounts by **MSISDN** (auto-creates T1, US-0.1). All sends are idempotent (idempotency key per OTP/notification) and rate-limited (anti-automation, §18). *Degradation:* primary route fails → secondary route; all routes fail → **queue with backoff**; **OTP falls back to email**; informational alerts degrade to **feed/push** so the user still gets the message in-app.

**EI-4 USSD aggregator (SY2).** Phase 2 feature-phone channel for report/track/alerts (US-3.9, UC-D02). `UssdGateway` port handles aggregator **session webhooks** (CON to continue, END to terminate); transient session state lives in **Redis** keyed by `MSISDN+sessionId` (ephemeral, §16). Flow: language → category → area ("use my registered area" or drill-down) → description → confirm → **ticket code returned by SMS** (EI-3). Account auto-linked by MSISDN. *Degradation:* USSD unavailable → **SMS-keyword fallback** for status/track; report **intake** falls back to SMS/app. Decoupled from app/web so a USSD outage never affects smartphone users.

**EI-5 Push — FCM/APNs (SY1).** Default real-time channel for report status, announcements, Q&A answers, petition milestones, project updates. `PushSender` port over **FCM HTTP v1** (Android/web) and **APNs token-based** (iOS); a **device-token registry** (per user, multi-device) is pruned on `unregister`/invalid-token responses. Payloads are **localised** (recipient language) and minimal (no sensitive body; deep-link only for private content). *Degradation:* **no valid push token → SMS fallback** (US-5.1); provider error → retry then SMS; the **feed always retains the item** regardless, so nothing is lost if all push fails.

**EI-6 Email/SMTP (SY1).** Transactional only (no marketing): OTP fallback, email-verification, password reset, staff/official case + SLA notices, digests. `EmailSender` port over a transactional ESP with **SPF/DKIM/DMARC**; **bounce/complaint webhook** flips `emailVerified=false` on hard bounce and suppresses further sends. Email is **not a uniqueness key** (D11) — a person may have several. *Degradation:* queue + retry; persistent failure → prefer SMS/push; **signup never blocks on email** (phone-OTP path remains, US-0.1).

**EI-7 Maps / reverse-geocoding (SY5-adjacent).** The pin-a-place → derive-both-geographies engine (§9.0). **Internal PostGIS point-in-polygon** against the **seeded official boundaries (EI-14)** is the **source of truth** for GPS→ward and the derived admin chain + Ward→Constituency mapping — this keeps civic routing accurate and **vendor-independent**. External map tiles/geocoding are optional, used only for display or as a hint when a GPS point misses internal polygons. *Degradation:* external maps down → **manual ward drill-down** (min pin = Ward) always works; civic routing never depends on a third-party geocoder.

**EI-8 Object storage + virus scanning (SY6).** S3-compatible store for report attachments, rep/profile media, and verification evidence, served via **pre-signed URLs** (§16). Uploads land in a **quarantine bucket**; the `MalwareScanner` port (ClamAV or hosted engine) must return **clean** before the object is promoted to a served bucket; **EXIF/geo stripped** for privacy (incident geo is captured separately on the Report). *Degradation:* scanner unavailable → object stays **quarantined and unserved**; the report itself is **accepted** and the attachment is delivered once the scan clears (no data loss, no unsafe media served).

**EI-9 Analytics / event pipeline (SY7).** Consumes domain events for the KPI/dashboard catalogue (Appendix C) — TTFR/TTR, SLA breach heatmaps, verification funnel T0→T3, channel mix, etc. **Strictly async and best-effort**; events are **pseudonymised** (ids hashed, no raw PII) before leaving the core. *Degradation:* sink down → buffer then drop-with-counter; the **durable event log permits backfill**. Analytics **never** sits on a request's critical path.

**EI-10 Search index (SY5).** **Postgres FTS at launch** (no external dependency); migrates to OpenSearch/Elasticsearch behind the same `SearchPort` when scale demands. Indexed asynchronously from events (UC-S05) over public entities (reps, projects, announcements, public reports). *Degradation:* external engine down or lagging → **fall back to Postgres FTS / direct DB query**; results may be less ranked but search stays available.

**EI-11 Parliament / Hansard (partner/curator).** Phase 2 accountability inputs (contributions, votes, attendance, sessions) for M6. Per D-Q4, Taarifu is **not the source of truth**: data is **curated by platform/partners** (with curator tooling) and may be self-added by reps under moderation; every item carries provenance (`sourceUrl`). *Degradation:* no automated feed required — manual curation is the baseline.

**EI-12 Election results / official rep lists (partner/curator).** Drives rep onboarding/claim verification (UC-A22/A28) and term transitions (UC-A31). **MVP uses the official MP/councillor list manually** (Admin verifies a claim against it before granting the `REPRESENTATIVE` role to an existing account, D12); a results feed is a Phase 2 nicety. This is the data backbone of the **ambitious local-leader onboarding program** (D-Q3) — an operations workstream, not a code dependency.

**EI-13 CDF / development-project data (partner/curator).** Phase 2 project tracking (M7): budgets, status, progress per area, fundingSource=CDF. Curated import + authorised-author entry with FKs to areas (D-Q4). *Degradation:* authorised manual entry is the baseline; bulk import is an accelerator, not a requirement.

**EI-14 Geography seed dataset (reference).** The **one-time official seed** (D-Q6) for the full hierarchy Region→Council/LGA→…→Hamlet plus Constituency and the **effective-dated `WardConstituency`** bridge (D14). Loaded via admin console **seed + bulk import**; re-delimitation/new districts handled by **effective-dated imports** so history never corrupts (§9.0). This is the substrate EI-7 routing depends on. *Degradation:* static once seeded — no runtime dependency.

**EI-15 Staff SSO/MFA (optional).** Optional OIDC for staff (Admin/Moderator/Area Official) mapping IdP groups → roles, with MFA. *Degradation:* falls back to **native staff login + TOTP MFA** (§18); never required for citizens.

**EI-16 App distribution + force-update (platform).** Play Store / App Store pipelines plus a server-driven **AppConfig** (min version, force-update, splash, feature flags) *(insight: MobAppConfig/Splash)*. The client enforces min-version locally; a store outage doesn't affect already-installed clients.

**EI-17 Voice-to-text (optional).** Swahili-capable STT behind a `SpeechToText` port for low-literacy/voice reporting (UC-D01) and USSD. Pure enhancement; absence degrades to text/photo entry.

**EI-18 Auto-moderation / content-safety (SY-mod).** The automated half of the **hybrid moderation** model (D-Q8): Swahili-aware profanity/PII/spam/image-safety scoring that **holds risky content for human review** (US-12.3). *Degradation:* provider down → **everything routes to in-house moderators + community flagging** — the human pipeline (MVP) is always the floor.

**EI-19 Secrets manager / KMS (platform).** Source for all credentials (§18: no secrets in source) and the **envelope-encryption keys** for field-level encryption of national/voter IDs and other PII. *Degradation:* leased keys cached; a KMS blip blocks new PII decryptions only, while non-PII paths stay up.

### 21.4 Cross-cutting integration NFRs

- **Timeouts & circuit-breakers** on every synchronous outbound call; open-circuit → immediate degraded path (no thread pile-up).
- **Retry policy:** exponential backoff + jitter, capped attempts, DLQ + alert on exhaustion; all retried operations idempotent (DI4).
- **Webhooks/callbacks** (DLR, verification, bounce, scan) are **authenticated** (signature/secret), **idempotent**, and **replay-safe**.
- **Cost & rate governance** per provider (SMS/USSD/verification volume caps, alerts on spend/error spikes) — SMS used sparingly (§15 cost-efficiency).
- **Sandbox/stub adapters** for every dependency enable **full E2E demos and tests with zero external calls** (supports region-by-region staged onboarding, D-Q5).
- **Provider exit / portability:** because each dependency is an adapter with a seeded internal fallback (boundaries EI-14/EI-7, Postgres FTS EI-10, operator-assisted verification EI-1/EI-2, human moderation EI-18), **no single vendor is load-bearing for launch** — consistent with the cloud-portable, in-country-where-feasible posture (D-Q9).

---

## 22. Search & Discovery

> A cross-cutting capability (module seam **M-Search**, served by **SY5 Search Index**). It powers two **first-class discovery flows** — *"find my representative"* and *"find my projects/issues by location"* — plus general lookup across the public civic graph. Search is **public-by-default but PII-safe**: it indexes only public entities and public fields, and excludes private reports, `ProfileLocation`s, and all profile PII.

### 22.1 Searchable entities & scope

| Entity | Indexed fields | Visible to Guest? | Notes |
|---|---|---|---|
| **Representative** (MP/Councillor/ward-exec) | name, party, constituency/ward, parliament role, `type`, `mandate`, status | ✅ | `FORMER` reps searchable, badged as historical |
| **Constituency (Jimbo)** | name, code, district/region | ✅ | links to its MP + member wards |
| **Administrative area** (Region→…→Ward/Village/Mtaa/Hamlet, Council/LGA) | name, code, parent chain, `LocationType` | ✅ | drives location-pickers & "near me" |
| **Project** (development/CDF) | name, sector, funding source, status, area(s), responsible office/rep | ✅ | Phase 2 (M7); aggregate progress only |
| **Announcement** | title, body, category, author, area scope | ✅ (published, non-expired) | respects `audienceScope`; drafts/expired excluded |
| **Public report** | code, title, category, area, status | ✅ (visibility = `PUBLIC` only) | **PRIVATE reports never indexed**; reporter identity never surfaced |
| **Issue category** | name (SW/EN), synonyms | ✅ | used for facet + report-routing UX |
| **Organisation** | name, type, area of operation | ✅ (verified orgs) | unverified orgs excluded |
| **Political party / Parliament** | name, abbreviation, term/session | ✅ | reference directory |
| **Petition / Survey / Poll** | title, target, status | ✅ (public, active/closed) | drafts and moderation-held items excluded |

**Excluded from all indexes (privacy invariant):** `ProfileLocation` (private PII — §9.0), profile contacts/IDs/demographics, `PRIVATE` reports and their content, internal `CaseEvent` notes, unpublished/draft/expired/moderation-held content, soft-deleted records. Citizen *people* are **not** a public search surface (only Representatives/Orgs in their civic capacity are).

### 22.2 Search modes & flows

- **Global typeahead (omnibox).** Single search box across reps, areas, constituencies, categories, projects, announcements, orgs; debounced suggestions (≤200 ms target), grouped by entity type with a clear label, max N per group, "see all" deep-link to faceted results.
- **By name.** Free-text over reps/orgs/projects/announcements; SW/EN aware (§22.5); fuzzy/typo-tolerant; diacritic- and case-insensitive.
- **By location.** Hierarchical drill-down (Region → … → Ward) **and** flat search of any area by name/code; selecting an area scopes a results view (reps, projects, public reports, announcements for that area).
- **By category.** Pick/typeahead an `IssueCategory` (with synonyms) to filter reports/projects/announcements.
- **Geo / "near me".** GPS → reverse-geocode to admin area + constituency (UC-B10) → returns *my representatives*, *projects in my area*, *public reports near me* (radius/area-bounded), *active announcements*. Works for Guests (device GPS, no account needed). PostGIS distance ranking when available; admin-area containment otherwise.

**First-class flow — "Find my representative" (US-2.1 / UC-C01).** Input = GPS **or** chosen ward/constituency (or, for a signed-in citizen, their `isPrimary` location by default). Output = the resolved **MP** (via Ward→Constituency mapping) + **Councillor (Diwani)** + **ward/village executive officer**, each with party, photo, term, contact, and a follow button. Never requires the user to know or type their constituency (§9.0). Available to Guests.

**First-class flow — "Find my projects/issues by location" (US-7.1 / US-3.7).** Input = a location (GPS, picked area, or a signed-in citizen's location). Output = development **projects** in that area (status/progress), and **public reports** in that area (map + list, upvote/follow). Private reports are excluded; a citizen's **own** reports (incl. private) appear via "My reports", not via public search.

### 22.3 Filters & facets

Per result type, facets are returned with counts:
- **Representative:** type {MP, COUNCILLOR, WARD_EXEC}, mandate, party, region/district/constituency, status {SITTING, FORMER}.
- **Report (public):** category, status (per §12.1), area level, priority, age/SLA bucket, has-attachments.
- **Project:** sector, funding source (e.g. CDF), status {PLANNED, ONGOING, STALLED, COMPLETED}, area, responsible office/rep.
- **Announcement:** category, author type (official/representative/org), area, recency.
- **Area:** `LocationType`, parent region/district/council.

Common controls: full-text `q`, area scope, date range, sort (relevance | recency | distance | popularity), standard `page,size,sort` envelope params (§17). Facet selections are composable (AND across facet groups, OR within a group).

### 22.4 Ranking

- **Text relevance** (FTS rank / BM25) as the base signal, boosted by field weight (name/title > body).
- **Geographic proximity** — closer/containing areas rank higher for location-scoped and "near me" queries (PostGIS distance or admin-containment depth).
- **Recency** — newer announcements/reports/projects float up; expired/`FORMER`/closed items are demoted, not hidden.
- **Authority/exactness** — exact code/name matches (ticket code, area code, rep name) pin to the top; `SITTING` reps over `FORMER`; verified orgs over the long tail.
- **Popularity (light)** — report upvotes/followers and project followers as a tie-breaker only (abuse-resistant; never overrides relevance or proximity).

### 22.5 Indexing approach (phased) & Swahili-aware tokenization

- **Phase 1 (MVP) — PostgreSQL FTS.** `tsvector` columns (or generated columns) + GIN indexes per searchable entity; `pg_trgm` for typeahead/fuzzy/typo tolerance; **PostGIS** (optional, §16) for "near me" distance. Indexes maintained transactionally / via the **transactional outbox** so search reflects committed state. This satisfies launch scale with no extra infrastructure.
- **Phase 2+ — OpenSearch/Elasticsearch (SY5).** When scale, multi-facet aggregation, or richer relevance demands it, project domain events (search-indexing events, UC-S05) into an external index. The search **API contract is stable across both** backends so clients are unaffected by the swap.
- **Swahili-aware tokenization.** Custom text-search configuration: SW + EN stop-words, **diacritic/accent folding**, lowercasing, light stemming, and a **civic synonym/alias dictionary** (e.g. *Mbunge↔MP*, *Diwani↔Councillor*, *Jimbo↔Constituency*, *Kata↔Ward*, *Mtaa/Kijiji/Kitongoji*, *maji↔water*, *barabara↔roads*, *afya↔health*). Bilingual queries match bilingual content; category synonyms map colloquial terms to canonical categories. The same analyzer config is mirrored in the OpenSearch phase.

### 22.6 Public vs authenticated scope

| Scope | Guest (T0) | Citizen (T1–T3) | Staff (Official/Mod/Admin/Root) |
|---|---|---|---|
| Public civic graph (reps, areas, projects, public reports, announcements, orgs, categories) | ✅ | ✅ | ✅ |
| Default location for "near me"/"find my rep" | device GPS | `isPrimary` location (or GPS) | as relevant to scope |
| **My** reports (incl. `PRIVATE`) | ➖ | 🔶 own only | 🔶 within assigned area/category scope (§Appendix A) |
| Internal case notes, verification queue, moderation queue | ➖ | ➖ | 🔶 scoped (Official/Moderator/Admin) |
| PII / `ProfileLocation` / contacts | ➖ | ➖ (own profile only, not via search) | ➖ except authorised verification context, audited |

Search authorization is **server-side and result-filtered** (deny-by-default, §7.1): the index stores a visibility/scope marker per document, and every query is constrained by the caller's role/scope and tier before results return — a private or out-of-scope document is never leaked, even by code or exact title.

### 22.7 Requirements (acceptance)
- **SR-1** Typeahead returns grouped suggestions across all public entity types; p95 < 300 ms; SW/EN + diacritic-insensitive; typo-tolerant.
- **SR-2** "Find my representative" resolves MP + Councillor + ward exec from GPS or a chosen ward/constituency, for Guests and citizens, without typing a constituency.
- **SR-3** "Near me" returns my reps, area projects, nearby **public** reports, and active announcements; private reports never appear.
- **SR-4** Faceted search with counts per result type; composable filters; standard pagination/sort envelope.
- **SR-5** **No PII or private report** is ever returned to an unauthorised caller; index excludes `ProfileLocation`, PRIVATE reports, drafts/expired/held content, soft-deleted records (verified by tests).
- **SR-6** Ranking blends relevance + proximity + recency + exactness; popularity is a tie-breaker only.
- **SR-7** Search backend is swappable (Postgres FTS → OpenSearch) behind a stable API; indexing is event/outbox-driven and reflects committed writes.

---

## 23. Tokens, Credits & Wallet **[DECIDED]**

> A **metering + monetization + anti-abuse** layer. Every token-spending action is metered, but **civic-core actions carry a free recurring quota** so no citizen is ever priced out of being heard (D18, your choice: *everything metered, civic gets a free quota*). Purchase is **Phase 2** (D19). One hard, non-negotiable fence governs the whole design:

> **🔒 Civic-integrity fence (binding):** tokens may meter **convenience, volume, speed, reach, and commercial** features. Tokens may **never** buy **democratic weight or truth** — no buying petition signatures, ratings, poll outcomes, report priority that distorts the public record, or verification status. One person = one signature / one rating / one binding vote, **regardless of token balance**.

### 23.1 Concepts
- **Wallet** — each `User` (and each `Organisation`/`ServiceProvider`) has a token balance.
- **Token** — an internal credit unit (not a cryptocurrency; no blockchain). Off-platform transfer/trading is disabled.
- **Free quota** — a recurring, role-based allowance (e.g. daily/weekly/monthly) that refreshes automatically and covers ordinary civic participation. Quotas are **config-driven** (admin-tunable per action, per role) and localised.
- **Ledger** — an append-only `TokenTransaction` log (grant / earn / spend / purchase / refund / expire / adjust), the source of truth for balances; balance = derived/cached.

### 23.2 What tokens meter (and what stays effectively free)
| Action class | Metering | Rationale |
|---|---|---|
| File an issue report (incl. official) | Costs tokens, but a **generous free quota** refreshes regularly | Anti-spam without blocking genuine reporting |
| Contact / ask a representative (Q&A) | Free quota, then tokens | Throttle floods, preserve access |
| Sign petition / vote in poll / rate a rep | **Effectively free** (small/zero cost) and **never balance-gated** — integrity fence | Democratic acts must not depend on wealth |
| Confirm/dispute a resolution, follow, browse, receive info | **Free** (never metered) | Core loop + consumption must be frictionless |
| **Boost / feature** a report or petition (more reach) | Tokens (reach only — never changes official routing/priority SLA) | Optional amplification, fenced from official handling |
| Create petitions/surveys/campaigns **beyond free quota** (esp. orgs) | Tokens | Higher-cost authoring; org/commercial use |
| Bulk announcements, premium analytics, API/export | Tokens / subscription | Commercial + provider/B2B features (§24) |
| Excess-volume actions beyond the free quota | Tokens | Uniform anti-abuse metering (your D18 choice) |

> If a citizen exhausts a civic-core free quota, the UX must clearly offer **(a) wait for refresh** (always free) before **(b) spend/earn tokens** — the free path is never hidden.

### 23.3 Earning, granting & buying
- **Grant on signup** — a starter balance.
- **Periodic free allowance** — auto-refreshing quota per role (the equity backstop).
- **Earn through good civic behaviour** (gamified, abuse-resistant): completing/verifying profile, verifying ID (T3), confirming resolutions, helpful/accepted contributions, reputation milestones. This turns the economy into a **positive-behaviour incentive**, not a paywall.
- **Purchase (Phase 2, D19)** via **mobile money** (M-Pesa, Tigo Pesa, Airtel Money, Halopesa) + card, through a `PaymentProvider` adapter (see §21 EI-20). Citizens buy small packs; orgs/providers buy larger packs or subscribe.
- **Refunds/adjustments** — admin-initiated, fully audited; failed/duplicate payments reconciled idempotently.

### 23.4 Domain entities (additions to §9)
- `Wallet`: owner (User|Organisation|ServiceProvider), balance (derived), freeQuotaState per metered action, status.
- `TokenTransaction` (append-only ledger): wallet, type {GRANT, EARN, SPEND, PURCHASE, REFUND, EXPIRE, ADJUST}, amount, balanceAfter, reason/actionCode, refEntity (report/petition/…), idempotencyKey, createdAt/By.
- `TokenPackage` (Phase 2): name, tokenAmount, price, currency, audience {CITIZEN, ORG, PROVIDER}, active.
- `Payment` (Phase 2): wallet, package, provider {MPESA, TIGOPESA, AIRTELMONEY, CARD}, providerRef, amount, status {PENDING, PAID, FAILED, REFUNDED}, idempotencyKey.
- `ActionCost` / `FreeQuotaPolicy` (config): per actionCode × role → cost + free-quota (period, count). Admin-tunable; versioned.
- `TokenReward` (config): per behaviour → token grant + caps (anti-farming).

### 23.5 Integrity, abuse & equity guardrails
- **Fence enforcement (server-side):** the binding-action endpoints (sign petition, rate rep, binding poll) **ignore token balance entirely** — they check tier + electoral scope + one-per-person only. Tokens can never appear in their authorization path.
- **No pay-to-distort:** "boost/feature" affects only **discovery/feed reach**, never a report's **official routing, SLA, or case priority** (those are set by category × area rules, §25.2), and never a petition's signature count or a rating's weight.
- **Anti-farming:** earning is capped per period and per behaviour; reward-able actions are validated (e.g. a *confirmed* resolution, not a self-confirmed loop); idempotent ledger prevents double-credit.
- **Anti-fraud (Phase 2 payments):** payment-provider webhook verification, idempotency, reconciliation, chargeback/refund handling, velocity limits.
- **Transparency:** users see their wallet, ledger history, and the cost/free-quota of any action before spending; quotas and costs are published.
- **Accessibility:** free quotas are sized so a typical citizen completes all normal civic activity **without ever needing to buy** tokens; USSD/SMS users get equivalent quotas (no purchase required on feature phones).

### 23.6 Phasing
- **MVP:** wallet + ledger + **free grants/quotas + earning + metering** (no purchase). Anti-spam value from day one; equity preserved.
- **Phase 2:** **purchase** via mobile money/card (`PaymentProvider`), token packages, org/provider subscriptions, premium/bulk features, B2B billing (§24).
- **Phase 3:** richer rewards/reputation, provider marketplace tie-ins.

> **Non-Goals update:** this supersedes the v1.0 "no payments" non-goal — **payments enter scope in Phase 2**, solely for token purchase/subscriptions (still no fundraising/donations/fines in v1).

---

## 24. Service Providers & Multisectoral Responders **[DECIDED]**

> Generalises "who you report to" beyond government. A single **Responder / Service-Provider directory** holds government agencies, parastatals, **and private companies**, each handling certain sectors/categories and areas, with their own staff and SLAs. Onboarding is **phased** (D20: utilities first, then banks/telecoms); private sector is a **paying B2B tier** (ties to §23 tokens/subscriptions). A report may involve **several responders** — handled as **one accountable owner + collaborators**, optionally split into linked sub-cases (D21).

### 24.1 The Responder model (generalises Area Official / Organisation)
- **`Responder`** (a capability of an `Organisation`): `responderType` {`GOVERNMENT_AGENCY`, `PARASTATAL` (TANESCO, DAWASA, water authorities), `PRIVATE_COMPANY`, `UTILITY`, `BANK`, `TELECOM`, `CIVIC_ORG`}; the **sectors/categories** it handles; its **geographic coverage** (areas, or nationwide); its **SLAs** (may differ from the platform default and may be contractual); status; verification.
- **Responder staff** = `User` accounts with a **scoped role** (`RESPONDER_AGENT` / `RESPONDER_ADMIN`) bound to the organisation + (areas, categories). This **subsumes and generalises `AreaOfficial`** (§6): a government Area Official is simply a responder of type `GOVERNMENT_AGENCY`. Existing RBAC scope rules (§7) apply unchanged.
- **Provider directory** is public (citizens can browse "who handles what"), but a provider's internal queues/notes are private to its staff.

### 24.2 Routing to providers (extends §25.2)
- Routing resolves `category → responder` by **sector + area**, or by a **provider the citizen selects** at report time when the category is provider-specific:
  - *Electricity outage* → **TANESCO** (regional office by area).
  - *Water* → the area's **water authority/DAWASA**.
  - *ATM/bank/account issue* → the **specific bank** the citizen picks (CRDB, NMB, …).
  - *Network/airtime* → the **specific telecom** the citizen picks.
- A `RoutingRule` maps `(category [, sub-category])` → responderType/sector, then narrows by **area** and/or **citizen-selected provider**. Government-area categories continue to route by admin level (§25.2). Falls back through the §25.2 ladder if no provider match.

### 24.3 Multisectoral issues (one owner + collaborators)
- A `Report` may carry **multiple `ResponderAssignment`s**: exactly **one `OWNER`** (accountable for closure) + zero-or-more **`COLLABORATOR`s**. The citizen tracks **one issue** with an **aggregated status**; each responder sees and acts on **its own slice**.
- **Optional split:** the owner (or an admin) can **split** a report into **linked sub-cases**, one per responder, each with its own §12.1 lifecycle; the parent aggregates child statuses and only closes when children resolve.
- **Coordination & visibility:** collaborators see the report’s public content + their assigned slice; **internal notes are per-responder/private** (a bank never sees an agency’s internal notes). A cross-responder “coordination” thread is available to assigned responders only.
- **Example:** a contractor cuts a water main and a TANESCO cable while repairing a road → owner = Roads agency; collaborators = Water authority + TANESCO; citizen sees one issue, aggregated status; each responder updates its part.

### 24.4 Onboarding, B2B & data-sharing
- **Phased onboarding (D20):** government + **parastatals/utilities** (TANESCO, DAWASA, water) first; **banks & telecoms** next; design supports any private company.
- **Provider workspace:** queue, assignment, SLA dashboards, analytics, team management — a **paying B2B tier** (subscription and/or §23 tokens). Pricing/packaging is a business decision (open item).
- **Verification:** providers are verified before going live (Moderator/Admin), with a verified badge; impersonation guarded.
- **Privacy & consent (PDPA):** sharing a citizen’s report (with PII) to a **private** company requires a **data-sharing basis + citizen consent**; providers see the **minimum** needed; cross-sector sharing is logged; sensitive-category reports (§25.3) have stricter rules. The citizen is told **who** their report goes to before submitting.
- **SLA governance:** each provider’s SLAs feed the same SLA/escalation engine (§25.2); breaches escalate within the provider and surface on the citizen’s timeline and platform analytics.

### 24.5 Domain entities (additions to §9)
- `Organisation` gains `responderType` + responder capability (or a `Responder` record 1:1 with the org).
- `Responder`: org, responderType, sectors/categories[], coverage(areas|nationwide), slaPolicy, status, verified.
- `ResponderAssignment`: report ↔ responder, role {OWNER, COLLABORATOR}, status, assignedAt/By, sla.
- `Report` gains optional `parentReportId` (for split/linked sub-cases) + aggregated status.
- `RoutingRule`: category/sub-category → responderType/sector → (area narrowing | citizen-selected provider) → default-ladder fallback.

### 24.6 Phasing
- **MVP:** generalized model in the schema; **government + parastatal/utility** responders onboarded (region-by-region per D-Q5); single-owner routing live; multisectoral **owner+collaborator** supported; provider directory browsable.
- **Phase 2:** **private companies** (banks, telecoms), provider **B2B billing/subscriptions** (§23), provider analytics, linked sub-case split UX, consent/data-sharing tooling.

> **Actors/RBAC update:** adds a **Service-Provider Responder** actor (private/parastatal) and `RESPONDER_AGENT`/`RESPONDER_ADMIN` roles, generalising `AreaOfficial`. The §Appendix F matrix’s “Area Official” column applies to all responder types (scoped to org + areas + categories).

---

## 25. Edge-Case & Gap Closures (completeness review)

> Closes the must-fix gaps from the completeness review. Each subsection is normative and refines the v1.0 sections it cites.

### 25.1 Data lifecycle, retention & erasure (resolves MF1)
Reconciles three tensions: **right-to-erasure** (PDPA), the **immutable audit log**, and **account/identity permanence** (D15, §6.4).
- **Erasure = anonymisation, not deletion of civic record.** On a verified erasure request (UC-A17/UC-S09), **PII is severed** (name, contacts, national/voter ID, `ProfileLocation`, demographics, media faces) and replaced with a **tombstone** (`anonymized_user_#`); the **civic record persists** (reports, case history, petition signatures count, ratings aggregates, audit entries) in de-identified form. This keeps counts/accountability intact while removing the person.
- **Audit log** stays immutable but stores **references/hashes, not raw PII**; erasure writes a *new* tombstone event rather than mutating history.
- **Per-entity retention (defaults, admin-configurable):**

| Data | Retention | On erasure |
|---|---|---|
| Profile PII, national/voter ID (encrypted) | Life of account | Severed + tombstoned |
| `ProfileLocation` | Life of account | Deleted |
| Reports & case history | Civic record (long-lived) | Reporter de-identified; content kept (PII redacted) |
| Petition signatures / ratings | Civic record | De-identified; **count preserved** |
| Attachments/media | Tied to host entity | Deleted/redacted if PII |
| OTP / sessions / verification evidence | Short (minutes–days) | Auto-expire |
| Audit log | Long (compliance) | Tombstone event added, never deleted |
| Analytics events | Pseudonymised from creation | N/A (no raw PII) |

- **Legal hold** suspends erasure for items under investigation (sensitive reports, fraud). **Erasure SLA:** acknowledge ≤72h, complete ≤30 days. **Non-prod PII (N8):** staging/test/demo use **synthetic or masked** data only; pilot prod PII is governed by the consent + retention rules above.

### 25.2 Report routing, coverage & the no-responder case (resolves MF2)
The "close-the-loop" promise needs a defined fallback where officials/providers aren’t yet onboarded (D-Q5 staged rollout).
- **Routing ladder (first match wins):** (1) provider-specific or area-official scope matching the **smallest covering area × category** (or citizen-selected provider, §24.2) → (2) parent **Council/LGA** office → (3) **District** office → (4) **regional/sector default queue** → (5) **platform operator queue** + Admin alert.
- **`UNROUTED` / `NO_RESPONDER` state:** if no live responder exists for an (area × category) cell, the report enters a visible **operator queue**, the citizen sees honest messaging (*“Recorded. No responding office is active here yet — we’re working on it; you’ll be notified of updates.”*), and it still accrues a public count (pressure signal). It is **never silently dead-ended.**
- **Coverage map (admin):** a live grid of (area × category) cells showing **live / pilot / not-yet-onboarded**, driving routing config and the citizen messaging above. Powers the D-Q5 region-by-region program and its KPIs.

### 25.3 Anonymous & sensitive-category reports (resolves MF3)
For sensitive categories only (corruption, GBV — D-Q1), with safety first.
- **Anonymous intake:** no `reporterProfile` stored; the reporter receives a **pseudonymous tracking token/code** (and an optional throwaway contact for status) to follow the case, add info, and confirm/dispute — **without any identity linkage**.
- **Forced protections:** sensitive-category reports are **forced `PRIVATE`** (never public/searchable, §20/SR-5); **incident location is coarsened/redacted** in any shared/aggregate view to protect the reporter/victim; attachments **EXIF/geo-stripped**.
- **Stricter handling:** elevated moderation + rate limits; restricted responder visibility (only the authorised handling office); **GBV duty-of-care** — surface support/referral resources and a defined escalation/referral path; never expose the reporter to the subject of the report.
- **Anti-abuse:** anonymity + rate-limit + auto-moderation balance against false/weaponised reports; repeated abuse from a device/MSISDN is throttled.

### 25.4 `isElectoral` edge cases (resolves MF5)
Binding civic actions hinge on this single field (§9.0/D13); specify every branch:
- **No voter-ID (most T2 users):** binding actions (rate MP, sign constituency petition, binding poll) require **T3**; a T3-via-**NIDA** user may use their **`isPrimary`** location as electoral (citizen-confirmed, cooldown-guarded) until/unless a **voter-ID** sets it authoritatively. (Policy default; admin-tunable.)
- **Voter-ID constituency ∉ any pinned location:** auto-create an **electoral-only `ProfileLocation`** for that ward/constituency (flagged, non-primary) so the authoritative electoral home is represented without forcing it as residence.
- **Conflict (voter-ID ≠ pinned):** **voter-ID wins** for `isElectoral`; the citizen is notified; pinned residence stays `isPrimary`.
- **Re-delimitation mid-term:** effective-dated `WardConstituency` (§9.0) **re-resolves** `isElectoral` on the effective date; affected citizens + in-flight petitions/ratings are notified; historical actions stay attributed to the constituency that was in effect when taken.
- **Cooldown:** manual `isElectoral` change default **once per 6 months** (config), audited; voter-ID verification bypasses cooldown.

### 25.5 Tier downgrade / revocation (resolves MF6)
- **Default (non-fraud downgrade, e.g. expired/withdrawn verification):** **prior valid actions stand** (signatures/ratings/reports remain counted); **new T3 actions blocked** until re-verified; in-flight nothing retroactively voided.
- **Fraud-driven revocation:** actions tied to the fraudulent identity are **invalidated**, and affected **aggregates recomputed** (petition counts, rating scores); reason-coded; user notified with an **appeal path** (§25.8).
- All downgrades/revocations are audited; binding-action integrity (one-per-person) is re-validated after recompute.

### 25.6 Empty, error & edge states (resolves MF8)
Required, localised (SW/EN), low-literacy-friendly states for high-traffic flows:

| Flow | Empty/Error/Edge state |
|---|---|
| Search / feed (new user) | No results → suggested follows (your area/reps/categories); offline → cached + “showing saved” banner |
| Find-my-rep | GPS outside seeded geography → prompt manual ward pick; seat vacant → “No sitting MP currently — view former / constituency” |
| File report | Offline → saved draft + queued badge; attachment too large/unsafe → clear guidance; scan pending → “photo will appear once checked”; no responder → §25.2 message |
| Verification | Provider timeout → “pending, you can keep using the app”; rejected → reason + retry/appeal |
| Sync (offline→online) | Conflict policy = **server-authoritative for status, client-preserved for unsent drafts**; queued actions applied in order; duplicates deduped by idempotency key |
| Notifications | No push token → SMS fallback notice; quiet hours → deferred (except transactional) |
| Payments (P2) | Mobile-money failure/timeout → idempotent retry, no double-charge, clear status |

### 25.7 Threat & abuse vectors (resolves SH5)
| Vector | Impact | Mitigation / detection |
|---|---|---|
| Sockpuppets / SIM-farm signups | Fake reports, brigading | Phone-uniqueness (D15), ID-dedup at T3, velocity/device limits, anomaly detection |
| Ratings brigading / coordinated petitions | Distort accountability | T3 + one-per-person + electoral scope; coordination/anomaly detection; tokens **cannot** buy weight (§23 fence) |
| Report flooding to harm a rep/office | Noise, SLA gaming | Rate limits + free-quota metering (§23), dedupe/merge, throttle per target |
| False abuse-flagging to suppress legit content | Censorship-by-flag | Flag reputation, threshold + human review, penalise abusive flaggers |
| GPS spoofing (fake incident location) | Misrouting, fake hotspots | Cross-check device GPS vs account locations; plausibility checks; manual review for anomalies |
| Representative/provider impersonation | Fraud, misinformation | Pre-go-live verification (Admin/Moderator), verified badges, claim verification (UC-A22) |
| Defamation in comments/ratings on public figures | Legal liability | Moderation, right-of-reply (§25.8), takedown policy, audit |

### 25.8 Moderation SLAs, governance & support (resolves SH6/SH7/SH8)
- **Moderation SLAs by severity:** GBV/safety/illegal → review target **≤ a few hours**; abuse/PII/spam → ≤24h; general → ≤72h. Queues prioritised by severity + virality.
- **Appeals:** handled by a **different** moderator than the original action; contested appeals escalate to **Admin**; defined appeal window + response SLA; outcomes audited (Appendix F footnote ᵉ).
- **Accountability-content governance (SH8):** representatives get a **right-of-reply** and a **dispute/correction** workflow for accountability facts (promises/contributions); defamation/takedown policy; rating-integrity rules; corrections logged.
- **Support & dispute (SH7):** in-app help/feedback + bug/wrong-routing report; support tiers; a **citizen↔official dispute path** beyond simple reopen (escalates to a supervisor/coverage owner) for contested resolutions.

### 25.9 Offline, i18n & accessibility ACs (resolves SH1/SH3/SH4)
- **Offline (SH1):** read-cache (feed, my reports, followed reps); **draft queue** for reports with retry/backoff and bounded retention; queued-action ordering + idempotent sync; conflict policy per §25.6.
- **i18n (SH4):** externalised strings with a **fallback chain** (missing SW → EN → key); **UGC language tagging** + Swahili-aware moderation language detection; **SMS/push template localisation** (GSM-7/UCS-2); Swahili pluralisation handled.
- **Accessibility ACs (SH3):** MVP stories gain ACs — **voice-to-text available** on report description (EI-17), **icon+label** navigation, WCAG 2.1 AA contrast/touch targets, audio/large-text options; a **low-literacy usability acceptance gate** before launch.

### 25.10 Decision-ID consistency (resolves MF7)
Canonical decision register going forward: **`D-Q1…D-Q10`** (the §19 product decisions) and **`D11…D21`** (design decisions: D11–D17 identity/geography §6.4/§9.0; **D18** tokens, **D19** payments-Phase-2, **D20** service-providers, **D21** multisectoral). The earlier §4.2 inline labels (`A1…`, `L1…`, `D3`, `D5`) are **superseded** — read `D3→D-Q3`, `D5→D-Q5`; assumptions are non-binding context only. The §19 table is the single source of truth for product decisions.

---

## 26. Risks & Mitigations

> Consolidated risk register for Taarifu v1–v3. Likelihood/Impact are **L/M/H**. **Owner/area** names the team that holds the mitigation (Eng = engineering; Program = partnerships/onboarding ops; T&S = trust & safety/moderation; Legal = legal/compliance; Product; SRE = ops/on-call). Risk IDs (R1–R34) are referenced by the delivery program and the §19 decisions they trace to. The two largest risks (**R1, R2**) are the ambitious onboarding programs behind **D-Q5/D5** and **D-Q3/D3** — they are *partnership/operations* risks, not software risks: the platform ships launch-ready while real participation is staged region-by-region.

### 26.1 Top risks (read first)

- **R1 — Nationwide agency onboarding stalls (D-Q5/D5).** If government departments do not adopt the case-management intake, reports route to officials who never respond, and the "close-the-loop" KPIs (TTFR, % resolved, TTR — §3.3) collapse. *This is the single biggest threat to product value.* Mitigation: **stage onboarding region-by-region; never open reporting in an area before its officials are live.**
- **R2 — Local-leader onboarding & verification is unachievable at the promised scale (D-Q3/D3).** Verifying and onboarding **MPs + Councillors + ward/village executive officers nationwide** is a sizable program; partial coverage means citizens "find no rep" for their ward and lose trust. Mitigation: software supports it from day one; **roll out leaders by the same region waves as agencies, MPs/Councillors first, ward/village execs as a backfill.**
- **R3 — Adoption fails on the inclusion edge** (low digital literacy, data cost, feature phones, Swahili-only users): the people Taarifu most exists to serve never use it.
- **R4 — A national-ID/PII breach** (NIDA/voter-ID data) — catastrophic for trust, citizen safety, and PDPA standing.
- **R5 — Integrity attacks on binding actions** (sockpuppet petitions, rating gaming, electoral-location gaming) discredit the accountability data.
- **R6 — Political/regulatory pressure** (government non-cooperation, censorship demands, election-period sensitivity) threatens the platform's neutrality and continuity.

### 26.2 Risk register

| ID | Risk | Category | Likel. | Impact | Mitigation | Owner/area |
|---|---|---|---|---|---|---|
| **R1** | **Nationwide agency onboarding stalls or lags (D-Q5/D5)** — reports route to non-participating offices; resolution loop never closes; TTFR/TTR/% resolved KPIs fail. | Delivery/Program | **H** | **H** | **Stage region-by-region; do not enable citizen reporting in an area until its officials are live and trained.** MoUs per department; named champions; "default office + Admin alert" fallback for unrouted reports (UC-D04); responsiveness dashboards (App. C) surface dead queues early; SLA escalation to higher area level (US-3.6). Phase agency waves to match marketing. | Program |
| **R2** | **Local-leader onboarding/verification unachievable at nationwide scale (D-Q3/D3)** — incomplete MP/Councillor/ward-exec coverage → "no rep found", broken Find-My-Rep, lost trust. | Delivery/Program | **H** | **H** | Generic `Representative` model ready day one; **roll out in the same region waves** (MPs + Councillors first, ward/village execs backfilled); bulk import from official lists (App. B); operator-assisted REP_CLAIM verification (D-Q2); show "rep being onboarded" state rather than empty. | Program |
| **R3** | Program coordination overload — two ambitious tracks (R1+R2) plus geography seed (D-Q6) run as partnership workstreams alongside engineering; under-resourcing delays launch. | Delivery/Program | **H** | **H** | Treat onboarding as a funded **operations program**, not an eng task; per-region launch checklist (geography seeded → officials live → leaders onboarded → marketing); region is the unit of "done"; explicit go/no-go gate per wave. | Program/Product |
| **R4** | Official geography/reference seed (D-Q6/D14) is incomplete, stale, or wrong (missing Councils/LGAs, ward→constituency mismatches) → mis-routing and wrong rep mapping. | Delivery/Technical | M | H | Seed from **official government dataset**; admin CRUD + bulk import (US-1.1); **effective-dated `WardConstituency`** so re-delimitation doesn't corrupt history; reverse-geocode validated against ward boundaries; data-quality review per region before go-live. | Program/Eng |
| **R5** | Re-delimitation / new districts / boundary changes break routing and electoral mapping mid-operation. | Technical/Operational | M | M | Effective-dated bridges and closure-table hierarchy (§9.0); migrations re-map without rewriting history; admin tooling to apply boundary changes; audit of remapping. | Eng |
| **R6** | **Low digital literacy & feature-phone reliance** — target users (e.g. P2, rural Singida) can't complete smartphone flows. | Adoption | **H** | **H** | Low-literacy flows (icons, voice-to-text, simple Swahili — §15); **USSD/SMS channel** for reporting/alerts (D-Q7, US-3.9); large-touch/high-contrast UI; on-the-ground community facilitators in pilot regions. | Product/Program |
| **R7** | **Data cost** deters use — citizens on tight data budgets (P1) avoid the app. | Adoption | **H** | M | Low-data mode, image compression/thumbnails, delta sync, pagination (§15/§20 cost-efficiency); offline drafts (US-3.1); SMS fallback; pursue zero-rating/reverse-billing arrangements with operators. | Eng/Program |
| **R8** | **Trust deficit** — citizens fear reporting reaches no one, or that complaining about authorities is risky; low uptake of high-trust actions. | Adoption | **H** | **H** | Visible status timelines and close-the-loop notifications (US-3.2); publish responsiveness stats; anonymous mode for sensitive categories (D-Q1); seed pilots where officials are demonstrably live (ties to R1). | Product/Program |
| **R9** | Empty-platform / cold-start effect — sparse reports, reps, and answers at launch make Taarifu feel inactive. | Adoption | M | M | Region-by-region launch concentrates density; pre-seed public reference content (reps, projects, announcements); partner orgs (NGOs, P5) drive initial campaigns. | Product/Program |
| **R10** | **NIDA/voter-ID PII breach** — leak of national IDs is catastrophic (citizen safety, PDPA penalties, total trust loss). | Security & Privacy | M | **H** | **Field-level encryption of IDs at rest** (§18); least-privilege access; PII redaction in logs; minimal retention; encrypted verification evidence; pen-test + SAST/container scan in CI; no national-ID data in non-prod; incident-response runbook. | Eng/SRE |
| **R11** | General breach / account takeover — credential stuffing, token theft, weak admin auth. | Security & Privacy | M | H | Rotating refresh tokens, OTP rate-limit + lockout, MFA for staff (§18); deny-by-default method-level RBAC; tight CORS allow-list (fixes prior `*`+credentials); secrets from manager, **no hardcoded credentials** (fixes prior repos); audited admin actions. | Eng |
| **R12** | **TZ PDPA (2022/2023) non-compliance** — consent, data residency, access/erasure obligations unmet. | Security & Privacy / Legal | M | H | PDPA-aligned design (§15): consent center, right to access/erasure (UC-A17/UC-S09), configurable retention, data minimisation; **in-country-where-feasible, cloud-portable** hosting (D-Q9); residency for ID data reviewed with Legal before launch. | Legal/Eng |
| **R13** | Over-collection of PII / scope creep on sensitive demographics increases breach blast radius and PDPA exposure. | Security & Privacy | M | M | Data minimisation by design; collect only what a tier/action requires; `ProfileLocation` and IDs treated as private PII, never shown publicly (§9.0); periodic data-retention review. | Product/Legal |
| **R14** | **Sockpuppet / multi-account abuse** to inflate signatures, ratings, upvotes, or reports. | Integrity & Abuse | **H** | H | **One account per person** — unique phone at signup + national/voter-ID dedup (block/merge) at verification (D11/D15); T3 gating on petitions/ratings/binding polls; device/velocity heuristics; rate limits + idempotency keys (§17). | Eng/T&S |
| **R15** | **Coordinated/astroturfed petitions & polls** — organised campaigns manufacture false consensus. | Integrity & Abuse | M | H | Binding actions require T3 + scoped to the **single electoral location** (D12/D13); one signature/response per profile (UC-E03); anomaly detection on signing velocity; moderation hold before public (US-9.1); transparency on signer counts. | T&S/Eng |
| **R16** | **Fake / malicious / spam reports** flood official queues and erode official goodwill. | Integrity & Abuse | **H** | M | Tiered reporting (community vs official — D-Q1); T2 for official reports; rate limits/queueing for low-trust; duplicate detection & merge (US-3.8); abusive-report hide/remove with audit; reputation signals. | T&S/Eng |
| **R17** | **Rating gaming** — brigading or self-dealing distorts representative scores. | Integrity & Abuse | M | M | One rating per period per rep, T3-only (US-6.2); **self-action block** (a rep can't rate themselves — D16); aggregate with abuse-resistant weighting; reps cannot edit/delete ratings; comment moderation. | T&S/Eng |
| **R18** | **Electoral-location gaming** — users hop `isElectoral` to influence multiple constituencies. | Integrity & Abuse | M | H | `isElectoral` is **voter-ID-authoritative** with a **change cooldown**, audited (D13); binding actions re-scope only after change settles (UC-A25/27); single-electoral-location invariant enforced server-side. | Eng |
| **R19** | **Multi-hat conflict of interest** — staff/reps act on their own work (resolve own report, answer own question, moderate own content). | Integrity & Abuse | M | M | **Self-action guardrails** block acting on self/own work (D16); context switcher records active "hat" (US-0.9); all multi-hat actions audited. | Eng/T&S |
| **R20** | **Harmful content at scale** — hate speech, GBV exposure, doxxing, incitement, misinformation in user content. | Content / T&S | **H** | H | Hybrid moderation (D-Q8): community flagging (US-12.1) + in-house moderators (US-12.2) + auto-assist (US-12.3); takedowns + appeals; stricter rules for sensitive categories; PII auto-detection to prevent doxxing. | T&S |
| **R21** | **Swahili-language moderation gap** — auto-moderation tooling and harmful-content classifiers underperform in Swahili (and local dialects/code-switching). | Content / T&S | **H** | M | **Swahili-aware** auto-assist (US-12.3, D-Q8) as *assist only*, human-in-the-loop; native-Swahili moderators; curated Swahili lexicons; conservative thresholds; never auto-remove without review for borderline cases. | T&S |
| **R22** | **Moderation can't keep pace** with content volume as adoption grows → backlog, slow takedowns, exposure window. | Content / T&S / Operational | M | H | Prioritised queues (US-12.2); auto-triage/hold of high-risk items; staffing plan scaled to volume; SLA on moderation actions; region-staged growth caps inflow. | T&S |
| **R23** | **Defamation / libel exposure** — citizens or the platform face legal action over allegations about named officials/reps. | Political/Legal | M | H | Moderation hold for new authors and accusatory content; clear content policy + reporting; right-of-reply for reps (Q&A, profile); T&S takedown + audit trail; Legal-reviewed terms; distinguish facts from opinion in UI guidance. | Legal/T&S |
| **R24** | **Government non-cooperation / withdrawal** — authorities decline to participate or pull out, gutting the responder side. | Political/Legal | M | **H** | Government-relations workstream with MoUs and named sponsors; demonstrate value via pilot wins; software usable for citizen↔rep engagement even where agencies lag; ties to R1 staging. | Program/Legal |
| **R25** | **Censorship / takedown / data-access pressure** — demands to remove lawful content or hand over user data. | Political/Legal | M | H | Clear, published policies; lawful-process-only data disclosure; minimise retained PII (limits what can be compelled); audit all takedowns; portable hosting (D-Q9) reduces single-jurisdiction lock-in; transparency reporting (P3). | Legal/T&S |
| **R26** | **Election-period sensitivity** — Taarifu perceived as partisan or as a campaign/electoral tool during elections. | Political/Legal | M | H | Explicit non-goal: **not an electoral/voting system** (§3.2); strict neutrality in moderation; election-window content rules; no candidate-promotion features; legal review of election-period operations; reps shown factually, not endorsed. | Legal/Product |
| **R27** | **Regulatory licensing / approval delays** — telecom shortcode, data-controller registration, hosting approvals slip the timeline. | Political/Legal / Operational | M | M | Start licensing/shortcode procurement early (D-Q7); PDPA data-controller registration with Legal; SMS-early/USSD-Phase-2 sequencing decouples launch from USSD approval. | Legal/Program |
| **R28** | **Scale / performance** — national scale (millions) or burst events (mass announcement fan-out) degrade latency/availability. | Technical | M | H | Async fan-out via event bus/outbox; caching, indexes, pagination (§15); extractable high-load modules (notifications/feed/search) from the modular monolith (§16); load testing per region wave; p95<500ms target. | Eng/SRE |
| **R29** | **SMS/USSD gateway unreliability or cost** — aggregator outages, delivery failures, or per-message cost block OTP, alerts, and feature-phone reporting. | Technical | M | H | Retries with backoff + delivery-status logging (US-5.2); idempotent sends; multi-aggregator/failover where possible; SMS used sparingly (§15); USSD session state in Redis; OTP not solely SMS-dependent (email option). | Eng/SRE |
| **R30** | **Verification-provider (NIDA) availability** — NIDA API access delayed, rate-limited, or unavailable, blocking T3. | Technical | M | M | **Pluggable adapter; operator-assisted at launch, NIDA later (D-Q2)** — *no launch dependency on NIDA*; moderator verification queue is MVP; graceful fallback when provider down (UC-A07/08). | Eng |
| **R31** | **Third-party dependency risk** — FCM, object store, search, email outages cause partial degradation. | Technical/Operational | M | M | Graceful degradation (feed/search read-only if a dependency is down — §15); circuit breakers/retries; SMS fallback when no push token; no single hard dependency on the mobile path. | Eng/SRE |
| **R32** | **Moderator/operator staffing** — too few trained, native-Swahili moderators and ID-verification operators for queue volume. | Operational | M | H | Staffing plan tied to adoption forecast; operator-assisted verification + moderation as funded roles (D-Q2/D-Q8); training + playbooks; community flagging offloads triage; region staging caps load. | T&S/Program |
| **R33** | **Support & on-call gaps** — citizen support (account/verification/report issues) and 24×7 incident response under-resourced. | Operational | M | M | Support runbooks + tiered escalation; in-app help in Swahili/English; on-call rotation with alerting (5xx, latency, SLA breach, queue depth — §15); status/incident comms plan. | SRE/Support |
| **R34** | **Key-person / institutional-knowledge concentration** during the clean rewrite (single team holds context superseding four prior repos). | Operational/Delivery | M | M | This PRD as the binding source of truth; OpenAPI/DDL/migrations as living artifacts; ≥80% test coverage + contract tests (§15); documented modular boundaries (§16). | Eng |

### 26.3 Risk-to-decision traceability

| Decision (§19) | Primary risks |
|---|---|
| D-Q5 / D5 (nationwide agency onboarding) | R1, R3, R8, R24 |
| D-Q3 / D3 (full local-leader onboarding) | R2, R3, R9 |
| D-Q6 / D14 (geography seed & hierarchy) | R4, R5 |
| D-Q1 (tiered/anonymous reporting) | R8, R16, R20 |
| D-Q2 (pluggable verification) | R30, R32 |
| D-Q7 (SMS early / USSD Phase 2) | R6, R7, R27, R29 |
| D-Q8 (hybrid moderation) | R20, R21, R22, R32 |
| D-Q9 (hosting/residency) | R12, R25 |
| D11–D13 (multi-location / electoral integrity) | R15, R18 |
| D15 (one account per person) | R14, R16 |
| D16 (role-conflict guardrails) | R17, R19 |
| D-Q10 / D17 (Swahili-first; Zanzibar Phase 2) | R6, R21 |

> **Risk posture.** The platform's *technical* risks (R10, R28–R31) are well-bounded by the architecture (§16) and the pluggable-verification decision (D-Q2). The decisive risks are **program and adoption** (R1–R3, R6–R8): they are won or lost in *partnerships and operations*, region by region — which is why **"region" is the unit of launch readiness** and why real onboarding is deliberately staged behind launch-ready software.

---

## 27. Rollout, Milestones & Data Migration

> Delivery is sequenced by **relative milestones**, not calendar dates. Each milestone has a **goal** and **exit criteria** (a gate the team must pass before the next). Two ambitious tracks — **D3 local-leader onboarding** and **D5 nationwide agency onboarding** — run as **parallel operations workstreams** alongside engineering; the software is built *nationally capable* while real participation is *staged region-by-region* to prove the resolution loop (D-Q5). Mainland-first; **Zanzibar deferred to Phase 2** (D17).

### 27.1 Phased delivery plan — modules → milestones

| Milestone | Modules delivered | Goal | Exit criteria (gate) |
|---|---|---|---|
| **M-Foundations** ("the spine") | **M0** (signup/OTP→T1, profile→T2, operator-assisted ID→T3 per D-Q2; single-account/additive-roles per D15), **M1** (geography + reference data), **M14** (admin console core), shared kernel (base entity, response envelope, RBAC + method security, Flyway), **M5** (transactional notification skeleton: push/SMS/email + outbox) | A clean, secure platform spine that can authenticate users, model the full TZ geography/electoral mapping, and let Admins onboard officials & representatives onto **existing accounts**. | Seed geography loaded & queryable (Region→Hamlet + Ward→Constituency, effective-dated); Root admin seeded via external secret (never hardcoded); RBAC method-security enforced & audited on every protected endpoint; OTP signup → T1 → T2 works in SW/EN; CI gates green (lint, test ≥80% core, SAST, container scan); `ddl-auto=validate`. |
| **M-MVP** ("close the loop") | **M2** (representatives + find-my-rep), **M3** (issue reporting & case management, full state machine §12.1), **M4** (announcements + personalised feed), **M5** (full push/SMS/email delivery, prefs, retries), **M12-basic** (flag → moderator queue, manual takedowns, ID/rep-claim verification queue), **M14** (queues/dashboards/config), SMS OTP & alerts live (D-Q7) | The **MVP definition of done (§8)**: a verified citizen finds their reps, files & tracks a routed report to resolution, and receives geo-targeted announcements — mobile + web, SW/EN. | A T2 citizen files a report that auto-routes by `category × area`, an Area Official triages → resolves, the citizen confirms/disputes (UC-D11/12/13), and both sides receive notifications — **end-to-end in a live pilot region** (§27.2); SLA clocks & escalation timers run; feed fan-out works at announcement burst; security review + load test passed (§27.5); moderation staffed; privacy/legal sign-off (D-Q9). |
| **M-Phase 2** ("depth & reach") | **M6** (accountability: contributions/attendance/promises/ratings — curated, D-Q4), **M7** (projects/CDF), **M8** (surveys & polls), **M9** (petitions), **M10** (Q&A), **M11** (discussions/comments, may slip to P3), **M12-full** (Swahili-aware auto-assist per D-Q8), **M13** (**USSD reporting pilot**, D-Q7), **M15** (analytics & dashboards), **Zanzibar** geography + `ZANZIBAR_HOR` legislature seed (D17) | Turn the loop into a full civic platform: engagement pillars live, feature-phone reach via USSD, accountability data visible, ops visibility via analytics. | T3 citizens sign petitions & rate reps with electoral-location scoping enforced (D13); USSD report→ticket→SMS-status round-trips in pilot; auto-moderation holds Swahili abuse with human-in-the-loop; analytics dashboards report TTFR/TTR/SLA/verification-funnel; Zanzibar reps modelled without schema change. |
| **M-Phase 3** ("scale & openness") | **M16** (organisations & campaigns), public read API / civic-data embeds, moderation transparency reporting, additional languages (i18n already wired) | Ecosystem & scale: orgs run campaigns, open civic data is consumable, platform hardened for national-scale load. | Org workspace with member roles & campaign analytics live; public API rate-limited & documented (OpenAPI); national-scale load profile met (§15); transparency report generated. |

**Cross-cutting throughout:** moderation (M12) staffing scales with content volume; notifications (M5) and observability (§15) are extended each milestone, never deferred wholesale.

### 27.2 D5 pilot strategy — nationwide-capable, region-staged

The case-management software is **built for all 26+ mainland regions on day one**; the *risk* is partnership-dependent department participation, not code (D-Q5). We de-risk by proving the resolution loop where officials are actually live before widening.

| Stage | Scope | Purpose | Advance when |
|---|---|---|---|
| **Pilot** | **1–2 pilot regions** (one urban e.g. Dar es Salaam council, one rural e.g. Singida — mirrors personas P1/P2), a focused set of categories (Water, Roads, Health, Sanitation) | Prove end-to-end: citizen report → auto-route → official triage → resolve → confirm, with real SLAs, in two contrasting contexts (smartphone + USSD/SMS reach). | TTFR/TTR within target (§3.3) for ≥1 full SLA cycle; ≥1 Area Official active per pilot council/category; resolution-confirmation working; no P1 security/privacy issues. |
| **Expand** | +3–5 regions; widen category coverage | Validate onboarding playbook repeatability and routing-config scaling across more LGAs. | Pilot KPIs hold while regions are added; official-onboarding runbook stable; escalation paths (supervisor / higher area level) exercised. |
| **National** | All mainland regions | Full nationwide intake. | Partnership coverage and moderator/ops staffing keep pace with inbound volume; analytics show no SLA-breach cliff. |

**Guardrails while staging:** outside live regions, reports still route to a **default area office + Admin alert** (UC-D04) with explicit "response times may vary" messaging — the software degrades honestly rather than dropping reports. A region is marked "live" in config only once its officials are onboarded and scoped.

### 27.3 D3 — Local-leader onboarding program (parallel ops workstream)

Onboarding & verifying **MPs + Councillors (Madiwani) + ward/village executive officers** nationwide is a sizeable program, gated by **operations, not software** (the `Representative` model handles all types from day one; D3/D-Q3). Run as an ops track that **feeds** the engineering milestones.

| Item | Approach |
|---|---|
| **Source of truth** | Official electoral/LGA lists (electoral commission MP & councillor rolls; council HR rolls for ward/village executive officers). Verify each claim against these before granting the `REPRESENTATIVE` role (UC-A22/A28). |
| **Sequencing** | **MPs first** (smallest set, highest visibility, constituency-mapped) → **Councillors** (ward-mapped) → **ward/village executive officers** (largest set; align with the **live pilot regions §27.2** so reps and report-routing go live together). |
| **Onboarding mechanics** | Admin grants the role to the person's **existing account** (citizen→rep, one identity, reused verified ID — D15/§6.4); attaches `type`/`mandate`/constituency-or-ward/party/parliament/term; status `PENDING_VERIFICATION → SITTING`. Special-seats/nominated MPs → `mandate ≠ CONSTITUENCY`, constituency FK null. |
| **Self-service path** | Reps may claim a profile → moderator verifies (`REP_CLAIM` VerificationRequest) → reduces central data-entry load as volume grows. |
| **Lifecycle** | Term end → `FORMER` (history retained, account & Citizen role persist); re-election re-activates on the same account (UC-A31). Re-delimitation handled by effective-dated `WardConstituency`. |
| **Conflict guardrails** | Onboarding cannot let a rep act on themselves (rate/petition/answer/moderate self) — D16 enforced platform-wide. |
| **Dependencies on eng** | Needs M0 (additive-role grant, rep-claim verification) and M1 (geography/party/parliament seed) — i.e. **M-Foundations** — before bulk onboarding starts; accountability authoring tooling (M6/M7) is Phase 2 (D-Q4). |

### 27.4 Data migration & seed strategy

**Honest position: the rewrite is overwhelmingly greenfield.** Per SYNOPSIS, the legacy estate is "a reference-data catalogue + an auth-only mobile shell"; the civic product (reporting, accountability, engagement, notifications, citizen↔place links) is ~0% built. **No legacy schema, API contract, or runtime data is binding.** The one genuinely valuable asset is the **reference-data catalogue**, which we *re-seed* (not lift-and-shift) onto the clean model.

**Seed sources (load in M-Foundations):**

| Data | Source | Notes |
|---|---|---|
| **Geography** | Official government dataset (D-Q6): Region → District → **Council/LGA** → [Division] → Ward → Village/Mtaa → Hamlet | Loaded onto the new hierarchy (closure-table / materialised path), **not** the legacy polymorphic `Area` mirror. **Council/LGA & optional Division are net-new levels** (D14) absent from legacy data → require enrichment from LGA sources, not a copy. |
| **Electoral mapping** | Electoral commission constituency boundaries | Build `WardConstituency` bridge, **effective-dated** (legacy had Constituency as a bare District leaf with no ward mapping — must be derived, not migrated). |
| **Political parties** | Registered-parties register | Re-seed onto `PoliticalParty` (legacy `PoliticalParty` is a usable content reference, but re-keyed to UUID + clean codes). |
| **Parliaments / terms / parliament roles** | Official parliament records | Re-seed; add `legislature` (Union vs Zanzibar HoR, Phase 2). |
| **Issue categories + SLAs + routing** | Defined with launch partners | New (no legacy equivalent): Water, Roads, Health, Education, Security, Corruption, Electricity, Environment, Sanitation, Land, Other (Appendix B). |
| **Root admin** | Externally-provided secret | Seeded securely on first run — **never** hardcoded/logged (fixes the legacy hardcoded-and-logged root password). |

**Legacy → target mapping (what, if anything, carries over):**

| Legacy repo | Carries over? | Disposition |
|---|---|---|
| `taarifu-core-api` | **Reference *content* only** (geography names/codes, parties, parliaments) — **re-seeded**, not migrated as rows | Re-key to UUID public ids + DB-sequence human codes; drop the `id+uid+code` triple, the polymorphic `Area` mirror, and copy-pasted audit. Treat its catalogue as a validated input file, re-imported via the new bulk-import tooling (UC-B08). |
| `taarifu-engine-api` | **No** | Earlier engine iteration; insight only. |
| `taarifu-engine-dash` | **No** (admin **UX patterns** as reference) | Rebuilt as the Angular admin console (M14). |
| `taarifu-mob-app` | **No** (aspirational `/mob/v1` contract never existed; defensive dummy data) | Rebuilt as the Flutter client against the real OpenAPI contract. |
| **User accounts** | **No migration** | Legacy users were admin/seed accounts on a non-booting API with hardcoded/weak credentials — **no production citizen data exists to migrate**. New accounts are created fresh via OTP signup (clean T0–T3 model). |

**Seed verification:** the imported catalogue must pass referential-integrity checks (every Ward resolves a full admin chain + a constituency; no orphan parents), be **idempotently re-runnable** via migrations, and round-trip a "find-my-rep" and a "route-a-report" query in each pilot region before that region is marked live.

### 27.5 Launch-readiness checklist (M-MVP gate)

Launch (first live pilot region) proceeds only when **all** of the following are signed off:

**Security & privacy**
- [ ] Security review complete (OWASP ASVS-aligned); no open P1/P2 findings; pen-test on auth, reporting, admin surfaces.
- [ ] RBAC **method-level** enforcement verified on every protected endpoint; deny-by-default; no "authenticated-only" admin surface (legacy defect closed).
- [ ] PII (national/voter ID) field-level encrypted at rest; PII redacted in logs; data-retention config set.
- [ ] **No secrets in source**; secrets from env/secret manager; Root seeded via external secret; CORS allow-listed (no `*`+credentials).
- [ ] **Legal/privacy sign-off** — Tanzania Personal Data Protection Act alignment; hosting/residency confirmed with legal (D-Q9); consent & right-to-erasure (UC-A17/UC-S09) working.

**Performance & reliability**
- [ ] **Load test** passed at expected pilot volume incl. announcement-burst fan-out and report-submission spikes; p95 reads <500ms, writes <1s (§15).
- [ ] Resilience verified: SMS/verification-provider retries & circuit-breakers; graceful degradation (feed/search read-only if a dependency is down).
- [ ] Observability live: structured logs (trace ids), metrics, tracing, health endpoints, alerts (5xx, latency, SLA breach, queue depth).
- [ ] Backups + tested restore; one-command rollback; migrations gated.

**Operations & content**
- [ ] **Moderation staffed** — moderator queue live; flag → action → audit → appeal path working; on-call coverage for abuse/safety.
- [ ] **Verification ops staffed** — operator-assisted ID & rep-claim review queues manned (D-Q2).
- [ ] **SMS shortcode procured & live** for OTP + alerts (D-Q7); deliverability tested with the aggregator.
- [ ] Push (FCM) configured; email sender authenticated (SPF/DKIM).

**Data & program readiness**
- [ ] **Seed data loaded & verified** — geography (incl. Council/LGA), constituencies, parties, parliaments, categories+SLAs+routing; referential integrity passed (§27.4).
- [ ] **Pilot region(s) "live"** in config: Area Officials onboarded & scoped (D5 §27.2); representatives onboarded for the region (D3 §27.3); routing rules resolve to a real office (default-office fallback + Admin alert configured).

**Product & accessibility**
- [ ] SW/EN strings complete for all launch flows; locale-aware formatting.
- [ ] Accessibility pass (WCAG 2.1 AA web/admin; large-touch/high-contrast/screen-reader mobile; low-literacy flows).
- [ ] Mobile force-update / min-version app config set; offline draft → sync verified.
- [ ] End-to-end smoke of the **MVP definition of done** in a real pilot region (find-my-rep → file report → route → resolve → confirm → notify), passed.

---

## 28. Key End-to-End Journeys

> **How to read this section.** These are concrete, step-by-step *wireflow-in-text* walkthroughs that thread multiple use cases (§11) into the lived experience of each persona (§5). Each journey calls out **channel**, **trust-tier gating** (§7.3), **notifications** (§13), and the **close-the-loop** moment. UC/US/D-IDs are referenced where natural. Journeys are illustrative of the **MVP** unless a step is tagged with its phase. They are the bridge between requirements and wireframes/OpenAPI design.

**Journey index**

| # | Persona | Channel(s) | Pillars exercised | Phase |
|---|---|---|---|---|
| J1 | **Amina** — smartphone citizen | Mobile (Flutter) | Identity, Reps, Reporting, Notifications | MVP |
| J2 | **Joseph** — feature-phone citizen | USSD + SMS | Reporting, Notifications | **Phase 2** (SMS alerts MVP) |
| J3 | **Hon. Neema** — MP | Web/PWA + Mobile | Reps, Announcements, Engagement | MVP→P2 |
| J4 | **Mr. Mushi** — District Water Official | Admin console | Reporting & Case Mgmt | MVP |
| J5 | **Faith** — Organisation | Web/PWA | Identity, Engagement (Petitions) | **Phase 2/3** |
| J6 | **Verification** — operator-assisted ID + voter-ID | Mobile + Admin | Identity, electoral location | MVP |

---

## J1 — Amina (smartphone citizen): sign up → verify → home + ancestral → find MP → report water → resolve

**Persona:** P1, Amina, 29, Dar es Salaam, low-end Android, low data budget, Swahili-first. **Channel:** Flutter mobile. **Outcome:** files a routed, tracked water report for her Dar ward and confirms its fix — while also following her ancestral ward in Rombo (Kilimanjaro).

**Phase A — Sign up & become Registered (T1)** *(UC-A01, UC-A03; US-0.1)*
1. Amina installs Taarifu, picks **Kiswahili** on the language splash (default; switchable, D-Q10). Lands on a browsable T0 guest home (she can already search reps/announcements without an account).
2. Taps **"Jisajili / Sign up"**, enters her **phone (+255…)**. System checks **one-account-per-phone** (D11/D15): no existing account → sends **OTP via SMS** (SY2; notification = *OTP, always-on*, §13).
3. Enters the 6-digit OTP (≤5 min expiry, attempt-limited, §US-0.1). Account created at **T1 — Registered**. She can now follow areas/reps, save items, take anonymous-eligible polls — but **cannot yet file an official report** (that needs T2).

**Phase B — Complete profile + set primary residence (T2)** *(UC-A04, US-0.2, D11 "Option A")*
4. App nudges her with a **civic-readiness** indicator ("Kamilisha wasifu — 2 hatua / Complete profile — 2 steps"). She enters name, gender, DOB; verifies her **phone** (already done) and confirms **email** (optional OTP, UC-A05).
5. **Set primary residence (one location at signup, D11):** she taps **"Tumia eneo langu / Use my location"** → GPS reverse-geocodes (UC-B10) to **Kinondoni → Ward (Kata)**; she confirms down to her **Mtaa**. The pin auto-derives both chains (§9.0): **administrative** (Dar es Salaam → Kinondoni Municipal Council → Ward → Mtaa) and **electoral** (Ward → Constituency/Jimbo). This becomes `isPrimary` **and**, by default, `isElectoral`.
6. With profile complete + contacts verified, she reaches **T2 — Profiled**. She is **not yet** ID-verified (that is T3, Journey J6) — and that's fine for reporting.

**Phase C — Add ancestral location (Rombo)** *(UC-A25/26; US-0.8, D12)*
7. In **Profile → Maeneo / My places**, Amina taps **"Ongeza eneo / Add place"**, drills Region **Kilimanjaro → Rombo District → Council → Ward**, and tags `associationType = HOME/ANCESTRAL`. She now holds **two** `ProfileLocation`s.
8. She keeps **Dar as `isPrimary`** (her default feed and default report area). `isElectoral` stays Dar for now (it only moves authoritatively via voter-ID, D13/J6). **Scoping consequence (§9.0 integrity rule):** she can **browse/follow/receive announcements** for *both* Dar and Rombo, but any **binding** action (rate an MP, sign a constituency petition) would apply **only to her single electoral location**. Locations are **private PII**, never shown publicly.
9. She **follows** (UC-C03/UC-G05) the Rombo ward and her Dar ward → both feed into her personalised feed (UC-G04) and notifications (US-4.3).

**Phase D — Find my MP** *(UC-C01/C02; US-2.1/2.2)*
10. From home she taps **"Wawakilishi wangu / My representatives"**. Because her primary place resolves an admin chain + constituency, the app shows, for **Dar**: her **MP (Mbunge)** for the constituency, her **Councillor (Diwani)** for the ward, and ward/village executive officers (D3). For **Rombo** she can switch the place selector to see those reps too.
11. She opens her Dar **MP profile** (UC-C02): photo, party, constituency, parliament term, **Follow** button (Phase-2 modules — contributions/projects/promises/ratings — appear as they ship, M6/M7). She follows the MP.

**Phase E — File the water report** *(UC-D01 → UC-D04; US-3.1)*
12. A communal water point near her Mtaa is dry. She taps **"Ripoti tatizo / Report an issue"** (allowed: she is **T2** ✅).
13. **Category** (UC-B14): picks **Maji / Water** (recents/popular surfaced). **Title + description** (voice-to-text optional for low-literacy ease). Attaches **2 photos** (≤ size/type limits, virus-scanned, EXIF/geo handled, SY6).
14. **Location of the incident:** defaults to **GPS** (which resolves to her **Dar ward**, *not* Rombo — report routes by **incident** location, §9.0 integrity rule), or she can hand-pick a ward. **Visibility:** leaves **PUBLIC** (default) so neighbours can upvote/follow (UC-D16).
15. **Offline-safe:** had she been offline, the report would store as a **draft and sync later** (UC-D03); low-data mode compresses images (§NFR cost).
16. Reviews summary → **Submit** (idempotent submit key, §17). System validates → scans → issues **ticket code `TAR-2026-0XXXXX`** → **auto-routes** (UC-D04) by **(category Water × Dar ward/Council level)** to the responsible **District Water Official's** scope (Mr. Mushi, J4) → creates `Report (status NEW)` + initial `CaseEvent` → starts the **SLA clock** (`dueAt = now + Water-category SLA`).
17. **Notification — close-the-loop begins (§13 "Report received (ack)"):** Amina gets an in-app + **Push** (SMS fallback if no push token) confirmation with her ticket code and **expected time-to-first-response**.

**Phase F — Track to resolution** *(UC-D05/D06/D12; US-3.2/3.5)*
18. **My reports** shows a live **timeline**. When Mr. Mushi assigns and starts work, status moves `NEW → ASSIGNED → IN_PROGRESS` (§12.1) and Amina receives a **Push + Feed** "status change" notification each time (§13, pref-gated).
19. The official posts a **public note** requesting a landmark photo → status `AWAITING_INFO`. Amina is notified, **adds info/comment to her own report** (UC-D06), status returns to `IN_PROGRESS`.
20. Mr. Mushi marks **RESOLVED** with a resolution note + a proof photo of the repaired valve (UC-D11). Amina is notified: **"Tatizo limetatuliwa? Thibitisha au Kataa / Resolved? Confirm or Dispute."**
21. **Close the loop (UC-D12):** Amina taps **Confirm** → status `RESOLVED → CLOSED`; the SLA clock stops as **met**. (Had the fix been fake, **Dispute** → `REOPENED → ASSIGNED`, re-queued with optional escalation; no action within window **W** → **auto-CLOSED** by system, UC-D13, still reopenable for period **R**.)
22. **Aftermath:** the closed case feeds analytics (TTFR/TTR, confirmation rate, §App. C). Amina later sees a **Water announcement** for her Dar ward (J3/J4 publish path) in her feed — the loop from "broken" to "informed" is complete.

---

## J2 — Joseph (feature phone): USSD report + SMS status **[Phase 2 for USSD; SMS alerts MVP]**

**Persona:** P2, Joseph, 54, rural Singida, **feature phone, no data**, Swahili-only. **Channel:** **USSD** session (interactive) + **SMS** (async status). **Phase note (D-Q7):** **SMS** (OTP + alerts) ships in **MVP**; the **USSD reporting** flow is the **Phase 2** parallel pilot (M13). Account is auto-linked by **MSISDN** (UC-D02/D09).

1. **Dial-in:** Joseph dials the procured **USSD shortcode** (e.g. `*XYZ#`). The SMS/USSD gateway (SY2) opens an ephemeral session (state in Redis, §16).
2. **Language:** menu prompts `1. Kiswahili  2. English` → he picks **1**.
3. **Auto-link / auto-register:** the gateway resolves his **MSISDN**. No account → silently create a **T1** account keyed to the phone (D15 one-account-per-phone); existing → reuse. No OTP friction inside USSD (the number is the channel).
4. **Main menu:** `1. Ripoti tatizo  2. Fuatilia ripoti  3. Taarifa za eneo langu  4. Msaada` (Report / Track / My-area alerts / Help).
5. **Report → Category:** picks **1**, then a paged category menu → **Maji / Water** (mirrors UC-B14 taxonomy, short labels).
6. **Location:** `1. Tumia eneo langu lililosajiliwa (Ward)  2. Chagua eneo` — Joseph picks **1** ("use my registered area"); if he had none, a guided **Region → District → Ward** drill-down captures the minimum **Ward** pin (enough to route, §9.0).
7. **Description:** guided/short free-text over the session (or concatenated SMS for longer text); confirmation screen summarises category + ward.
8. **Submit:** he confirms → system runs the **same** UC-D04 auto-routing as the app → issues a **ticket code** → **closes the USSD session** with "Imepokelewa. Namba: TAR-2026-0XXXXX."
9. **SMS acknowledgement (§13, MVP):** an **outbound SMS** repeats the ticket code and the expected first-response time, so Joseph has it after the session ends.
10. **Status updates by SMS (US-3.2/UC-G07):** every status change (`ASSIGNED → IN_PROGRESS → RESOLVED`) is pushed to him as a **concise SMS** in Swahili, rate-limited and idempotent (§13 rules; SMS used sparingly, §NFR cost).
11. **Close the loop on a feature phone:** the **RESOLVED** SMS includes a **reply convention** — *"Jibu 1=Thibitisha, 2=Kataa"* — mapping his SMS reply to **Confirm (→ CLOSED)** or **Dispute (→ REOPENED)** (UC-D12). No reply within window **W** → **auto-CLOSED** (UC-D13).
12. **Area alerts:** menu option **3** subscribes him to **SMS announcements** for his ward (UC-G05/G07), so authority/representative announcements (J3/J4) reach him with **no smartphone and no data** — fulfilling the inclusive-reach objective (O4).

---

## J3 — Hon. Neema (MP): granted Representative role on her existing account → announce → answer Q&A → respond to petition

**Persona:** P3, Hon. Neema, newly elected **MP (Mbunge)** who was already a Taarifu **citizen**. **Channel:** Web/PWA (primary for authoring) + Mobile. **Key principle:** **one account, additive roles** (§6.4, D12/D15) — *no re-registration*. **Conflict guardrails (D16)** apply throughout.

**Phase A — Role granted on her existing account** *(UC-A28/UC-A22; US-0.6)*
1. After the election, an **Admin** (Grace, P7) searches Taarifu for Neema's **existing citizen account** by name/phone/ID (UC-A23). The platform **never creates a second account** — if none existed, she'd be invited to create one first.
2. Admin **verifies the claim** against the official results / sworn-in MP list, then attaches a `Representative` record: `type = MP`, `mandate = CONSTITUENCY` (constituency FK set; for **Special-seats/Viti Maalum or Nominated** the mandate differs and the constituency FK is **null**, §9.1), party FK, `legislature = UNION_PARLIAMENT`, current parliament/term, status `SITTING`. The **`REPRESENTATIVE` role** is granted to the **same `User`** (D12).
3. **Notification (§13 role/account):** Neema receives "Umepewa jukumu la Mwakilishi / You've been granted the Representative role." She logs in with her **same credentials** — same profile, same history, **plus** a new **"Act as MP" context** (US-0.9 context switcher). She **retains the Citizen role** (she can still report a pothole on her own street *as a citizen*).

**Phase B — Publish a geo-targeted announcement** *(UC-G01/G02/G03; US-4.1)*
4. In the **"as MP"** context she opens **Announcements → New**. Composes title + body (Swahili + English), selects **audience scope = her constituency** (optionally narrowed to a ward or a category), schedules `publishAt/expireAt`, and picks **channels = FEED + PUSH (+ SMS optional)**.
5. As a **new author**, her first announcement goes through a **moderation hold** (UC-G03, Dr. Salum's queue) before publish; established authors skip the hold.
6. On publish, the system **fans out** (UC-S02) to every subscriber whose locations match the constituency — including Amina (if her electoral/primary place is in-scope) and **Joseph by SMS** — and queues notifications per preference and **language** (UC-S03, §13 "New announcement in my area"). **Close-the-loop:** constituents are informed where they live, on the channel they can receive.

**Phase C — Answer a citizen question (Q&A)** *(UC-E09/E10; US-10.1/10.2) [P2]*
7. A constituent (T2+, e.g. Amina) asks Neema a **public question** (rate-limited, moderated, upvotable). It lands in Neema's **Q&A inbox** for her profile only.
8. Neema **Answers** (published) or **Declines with reason** (UC-E10). **Guardrail (D16):** she can only answer questions **targeting her**, and cannot answer a question targeting herself *as a citizen* — staff multi-hat actions are audited.
9. **Notification (§13 "Q&A answered"):** the asker **and upvoters** are notified (Push + Feed). Her **responsiveness** (% answered in 14 days) feeds her accountability KPIs (§App. C; success metric ≥ 50%).

**Phase D — Respond to a petition that reached threshold** *(UC-E04/E05; US-9.3) [P2]*
10. A petition **targeting Neema/her office** (created by Faith's org, J5) crosses its `signatureGoal`. The system fires **UC-E04**: petition status → `SUCCEEDED`, and **notifies the target (Neema)** and all signers (§13 "Petition reached threshold").
11. Neema, in MP context, opens the petition and **publishes a response** (UC-E05) → status `RESPONDED`. **Guardrail (D16):** a representative **cannot sign or create** a petition against **themselves**; she may only **respond** as the target.
12. **Close-the-loop:** all **signers are notified** of her response (Push/Feed/Email per pref). The petition's lifecycle (`ACTIVE → SUCCEEDED → RESPONDED → CLOSED`, §12.2) is now visible publicly — accountability made legible.

> **Term end (UC-A31, D12):** when Neema's term ends, an Admin transitions the Representative record to **`FORMER`** (history retained, capabilities revoked) — **her account and Citizen role persist**, and re-election simply re-activates the role on the **same account**.

---

## J4 — Mr. Mushi (District Water Official): receives routed report → triage → request info → resolve with proof → SLA/escalation

**Persona:** P4, Mr. Mushi, **Area Official** for **Water × his District/Council** (AC5). **Channel:** **Angular admin console** (RBAC-scoped to his areas + categories). **Outcome:** works Amina's J1 report through the case state machine (§12.1) to a confirmed close, under SLA.

**Phase A — Onboarded & scoped** *(UC-A21; US-0.6)*
1. Grace (Admin) onboarded Mr. Mushi: granted the **`AREA_OFFICIAL`** role with a **RoleAssignment** scoped to `areaIds = [his District/Council + wards]` and `categoryIds = [Water]` (§9.1). **Server-side method security** ensures he only ever sees in-scope cases (§7.1; deny-by-default).

**Phase B — Triage queue** *(UC-D07; US-3.3)*
2. He opens his **triage queue** — automatically filtered to **Water reports in his District** (Amina's `TAR-2026-0XXXXX` appears, routed by UC-D04). He sorts/filters by **status / priority / age / SLA remaining**; **SLA timers** are visible per row (breach-soon highlighted).
3. He opens the report: sees category, description, **photos**, **incident location** (ward/Mtaa, GPS), reporter (pseudonymous handle; her `ProfileLocation` PII is **not** exposed), and any **upvotes/followers** (UC-D16) signalling a **possible duplicate** cluster.

**Phase C — Assign & act** *(UC-D08/D09/D10; US-3.4)*
4. **Assign** to himself (or a colleague in scope) → `NEW → ASSIGNED`; then **Start** → `IN_PROGRESS`. **Guardrail (D16):** an official cannot resolve **their own** filed report — separation of duties is audited.
5. He adds an **internal note** (visible to officials only) coordinating a field crew, and a **public note** to Amina. Needing a landmark, he **Requests info** (UC-D10) → `IN_PROGRESS → AWAITING_INFO`; Amina is notified and replies (J1 step 19) → back to `IN_PROGRESS`.
6. **Duplicate handling (UC-D15, P2):** if three neighbours filed the same dry water point, a Moderator/official **merges duplicates** (`duplicateOf`), so effort isn't doubled and upvote weight consolidates.

**Phase D — Resolve with proof** *(UC-D11; US-3.4)*
7. Crew repairs the valve. Mr. Mushi sets **RESOLVED** — **resolution note is required**, plus an **optional proof photo** (he attaches the repaired-valve image). System emits status change → **Amina notified to Confirm/Dispute** (J1 step 20, §13).
8. **Confirmed (UC-D12):** Amina confirms → `RESOLVED → CLOSED`; SLA recorded as **met**; the case contributes to his office's **TTR / confirmation-rate** dashboards (§App. C).

**Phase E — The SLA / escalation path** *(UC-D14, UC-S01; US-3.6) [escalation P2]*
9. **What if he'd stalled?** The **Scheduler** (SY4, UC-S01) ticks the **SLA clock**. As `dueAt` approaches, "**SLA breach imminent**" notifies Mr. Mushi (and his supervisor) (§13 role-channel). On breach, the case enters **`ESCALATED`** (stays active) and is routed up to a **supervisor / higher area level** (Council → District) (UC-D14); the escalation is **logged and audited**.
10. **Net loop:** whether resolved on time or escalated, **every transition is enforced server-side, notified to the right party, and auditable** (§12.1, §NFR auditability) — the case never silently dies, which is exactly what the "close-the-loop" objective (O3) demands.

---

## J5 — Faith (Organisation): register org → create petition → reach threshold → representative responds **[Phase 2/3]**

**Persona:** P5, Faith, coordinator of a civic **NGO** (AC3). **Channel:** Web/PWA. **Phase note:** Organisations (M16) are **Phase 3**, Petitions (M9) **Phase 2** — this journey is forward-looking but uses only modelled capabilities. **Tier gating:** creating an org and signing petitions require **T3** (§7.3).

**Phase A — Register & verify the organisation** *(UC-A18/A19; US-0.5)*
1. Faith (already a **T3 ID-verified citizen**, via J6) opens **"Sajili shirika / Register organisation"**. She creates an **`ORGANIZATION` profile** (name, mandate, contacts) and **uploads registration documents** (NGO certificate).
2. The request enters the **Moderator verification queue** (UC-H04, Dr. Salum). On approval, the org is **verified**; Faith becomes **`ORGANIZATION_ADMIN`** and can **invite/manage members** (UC-A20). The org gains org-level capabilities (campaigns, petitions, aggregated reporting) **without** Faith losing her personal citizen identity (additive roles, §6.4).

**Phase B — Create a petition** *(UC-E01/E02; US-9.1)*
3. In the org workspace she creates a **Petition**: title, body, **target = the constituency's MP/office** (Hon. Neema, J3), a **`signatureGoal`**, and a **deadline**. Status starts `DRAFT`.
4. **Moderation before public (UC-E02):** the petition is held for Moderator review (quality + safety), then goes **`ACTIVE`** and shareable (deep link + social share).

**Phase C — Citizens sign (threshold)** *(UC-E03/E04; US-9.2)*
5. Citizens sign via app/web — **but only T3 (ID-verified) citizens may sign** (US-9.2), **one signature per profile** (atomic count, idempotent, §17), with an **optional comment** and a **public/anonymous** choice.
6. **Electoral-location integrity (D13):** a **constituency petition** is a **binding civic action** — it counts a signer **only against their single `isElectoral` location**. So Amina (J1) could sign a **Dar** constituency petition (her electoral place), but signing a **Rombo** petition would require her electoral location to be Rombo — preventing double-influence across her two places.
7. On crossing `signatureGoal`, **UC-E04** fires: status `ACTIVE → SUCCEEDED`; the **target (Neema) and all signers are notified** (§13 "Petition reached threshold").

**Phase D — Representative responds → close the loop** *(UC-E05; US-9.3)*
8. Neema responds (J3 Phase D) → status `RESPONDED`. **All signers are notified** of the outcome (Push/Feed/Email).
9. **Aftermath:** Faith's org sees **campaign analytics** (signatures over time, geographic spread, §App. C / M16). The petition's full public lifecycle (`ACTIVE → SUCCEEDED → RESPONDED → CLOSED`) closes the loop from **collective demand** to **on-record response** — the core promise of the Engagement pillar.

---

## J6 — Verification journey: operator-assisted ID + voter-ID setting the electoral location

**Persona:** any citizen at **T2** seeking **T3** (using Amina from J1 as the subject). **Channels:** Mobile (citizen capture) + Admin console (moderator review). **Decisions in play:** **D-Q2** (operator-assisted at launch, NIDA later — pluggable adapter), **D13/D15** (voter-ID sets electoral location; national/voter-ID **dedup**), **§18** (PII encryption).

**Phase A — Start ID verification** *(UC-A07/A08; US-0.3)*
1. Amina (currently **T2**) wants to **sign a petition / rate her MP** — actions gated at **T3**. The app shows a clear gate: *"Thibitisha kitambulisho ili kuendelea / Verify your ID to continue."*
2. She opens **Verify ID** and chooses an **`idType`**: **`NATIONAL` (NIDA)** or **`VOTER`**. She enters the **`idNo`** and (optionally) uploads a **document photo + selfie** (encrypted in transit and at rest; PII redacted from logs, §18).

**Phase B — Dedup & adapter routing** *(UC-A29; UC-S04)*
3. **Dedup first (D15):** the system checks whether the ID **already belongs to another account**. If so → **block (or initiate merge)** (UC-A29) so one person can't hold two identities. Amina's is new → proceeds.
4. **Pluggable verification adapter (SY3, D-Q2):**
   - **At launch (operator-assisted):** the NIDA automated path is not yet integrated, so the request becomes a **`VerificationRequest (PENDING)`** queued to a **Moderator**.
   - **Later (NIDA API):** the same interface calls the provider; on a clean automated match it **auto-approves** (UC-S04 callback) with no human step. *The citizen experience is identical; only the backend implementation differs.*

**Phase C — Operator review** *(UC-H04/UC-A08; US-12.2)*
5. Dr. Salum (Moderator) opens the **verification queue**, compares the submitted **idNo / document / selfie** against the evidence, and decides: **Approve**, **Reject (with reason)**, or **Request more info** (`PENDING → APPROVED | REJECTED | MORE_INFO`, §12.2). Every action is **audited** (§NFR).

**Phase D — Approval, tier upgrade, and the electoral location** *(US-0.3; D13)*
6. On **Approve**: `Profile.idVerified = true`, `verifiedAt/By` stamped, and the account's **`trustTier` upgrades T2 → T3**. Amina is **notified** ("Kitambulisho kimethibitishwa / ID verified").
7. **Voter-ID is authoritative for the electoral location (D13):** because Amina verified with **`idType = VOTER`**, the system **sets her `isElectoral` location authoritatively** to the ward/constituency tied to her voter registration — **overriding** the default that had been her primary (Dar). If her voter registration is in **Rombo**, her **binding civic weight now sits in Rombo**, even though **Dar remains `isPrimary`** (default feed/report area). *(Had she verified with NIDA only, `isElectoral` would stay at its current value.)*
8. **Anti-gaming (D13):** any **manual** change to `isElectoral` thereafter is **rate-limited (cooldown)** and **audited**; binding actions re-scope to the new electoral location only **after the change settles** (UC-A27).

**Phase E — Newly unlocked, close-the-loop**
9. Back in the app, the **T3 gates lift**: Amina can now **sign petitions** (J5 — counted against her **electoral** location, D13), **rate her MP** (UC-F05, **only** her electoral-location MP), **vote in binding polls**, and **create an organisation** (J5 Phase A). Her **verification funnel** event (T2→T3) feeds the verification KPI (§3.3 target ≥ 40%; §App. C).
10. **Net:** identity is verified **once**, **deduplicated**, **encrypted**, and **permanent**; the **electoral location** — the single anchor of binding civic influence — is set from the **authoritative voter source**, cleanly separating *where she lives and reports* (Dar, primary) from *where her vote-weight counts* (her registered ward) without fragmenting her one account (§6.4).

---

> **Cross-journey threads (design checklist).** Every journey demonstrates: (a) **one account, additive roles** (J3 MP, J5 org-admin, J6 verified-citizen all on a single identity, §6.4); (b) **tier gating enforced server-side** (T1 browse → T2 report → T3 sign/rate, §7.3); (c) **channel-appropriate notifications** with **SMS fallback** for reach (J2/Joseph, §13); (d) **the loop always closes** — ack → status → resolve/respond → confirm — or **escalates** rather than dies (J4, §12.1, O3); and (e) **administrative vs. electoral location** kept distinct so reporting routes by *incident* place while binding influence stays on the *single electoral* place (J1/J6, §9.0, D12/D13).

---

## Appendix A — RBAC Permission Matrix (representative excerpt)

Legend: ✅ allowed · 🔶 own/scoped only · ➖ not allowed · (T#) tier-gated.

| Action | Guest | Citizen | Org | Representative | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|
| Browse public content | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Sign up / manage own account | — | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| File official report | ➖ | ✅(T2) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Track own report | ➖ | 🔶 | 🔶 | 🔶 | ✅(scope) | ✅ | ✅ | ✅ |
| Triage/assign/resolve case | ➖ | ➖ | ➖ | ➖ | 🔶(scope) | ➖ | ✅ | ✅ |
| Publish announcement | ➖ | ➖ | 🔶 | 🔶(constituency) | 🔶(scope) | ➖ | ✅ | ✅ |
| Create petition | ➖ | ✅(T3) | ✅ | ➖ | ➖ | ➖ | ✅ | ✅ |
| Sign petition | ➖ | ✅(T3) | ➖ | ✅(T3) | ✅(T3) | ✅(T3) | ✅ | ✅ |
| Ask question to rep | ➖ | ✅(T2) | ✅ | ➖ | ➖ | ✅ | ✅ | ✅ |
| Answer question | ➖ | ➖ | ➖ | 🔶(own) | ➖ | ➖ | ✅ | ✅ |
| Rate representative | ➖ | ✅(T3) | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ |
| Publish contribution/project | ➖ | ➖ | ➖ | 🔶(own/authorised) | 🔶(scope) | ➖ | ✅ | ✅ |
| Moderate content / verify IDs | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ | ✅ |
| Manage reference data/config | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| Manage admins / system settings | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶 | ✅ |

*(Full matrix maintained as a living artifact alongside the codebase.)*

## Appendix B — Reference Data & Seed
- Tanzania geography (Regions → Hamlets + Constituencies) from official source.
- Political parties (registered), parliaments/terms, parliament roles.
- Issue categories + default SLAs + routing (Water, Roads, Health, Education, Security, Corruption, Electricity, Environment, Sanitation, Land, Other).
- Seed a Root admin via **secure, externally-provided** credentials (never hardcoded).

## Appendix C — Analytics & KPIs catalogue
Reports volume by area/category/time; TTFR/TTR distributions; SLA compliance & breach heatmap; resolution & confirmation rates; reopened rate; representative responsiveness (Q&A, petitions); engagement (signatures, poll responses, follows); verification funnel (T0→T3); channel mix (app/web/USSD); language usage; notification delivery rates; moderation actions & abuse rate; project delivery/progress; geographic participation heatmaps.

---

*End of PRD v1.0 (Draft). All §19 decisions are resolved (2026-06-22); the PRD is ready for development approval. This document is the basis for system design, API specs, and the implementation backlog.*

---

## Appendix D — Issue Category Taxonomy, SLA & Routing

> **Purpose.** A seed taxonomy of citizen-reportable issues for Tanzanian local civic life, with **default routing level**, **default SLAs** (time-to-first-response *TTFR* / time-to-resolution *TTR*), **sensitivity** (anonymous-eligibility per **D-Q1**), and **default visibility**. These are *defaults*: all rows are admin-configurable per **US-1.2 / UC-B14 / UC-B15** and re-routed by the engine in **UC-D04**. Categories are **hierarchical** (`IssueCategory.parent`, §9.1) and carry `defaultRouting`, `defaultSLA`, `sensitive`, `defaultVisibility`, and `icon`. Routing resolves to the **smallest covering admin area × category** Area-Official scope (§9.0 / §7.1), falling back up the chain (Ward → Council/LGA → District → Region) and finally to a default office + Admin alert when no scope matches.

### D.1 Routing-level legend (maps to the §9.0 administrative chain)

| Token | Resolves to | Typical responder |
|---|---|---|
| **WARD** | Ward (Kata) office | Ward Executive Officer (WEO/Mtendaji wa Kata), Councillor (Diwani) for political follow-up |
| **MTAA/VILLAGE** | Mtaa / Village (Kijiji) | Village/Mtaa Executive Officer (VEO/Mtendaji) |
| **COUNCIL** | Council / LGA (Halmashauri) sector department | Council sector officer (e.g. Council Water Engineer, Council Health Officer) |
| **DISTRICT** | District administration | District department head / DAS office |
| **REGION** | Regional secretariat (RAS) | Regional sector officer |
| **SECTOR/UTILITY** | National agency / parastatal scoped to area | e.g. **TANESCO** (electricity), **DAWASA/RUWASA** (water), **TARURA/TANROADS** (roads), **TBA**, **NEMC** |
| **OVERSIGHT** | Independent oversight body | e.g. **PCCB/TAKUKURU** (corruption), Police/**TPF**, GBV desk, **CHRAGG** |

> Routing tokens are an **abstraction**, not hardcoded agencies. Each token maps, per area, to one or more onboarded Area-Official scopes (or a "default office" placeholder until that agency is live — see **D-Q5** staged onboarding). Where a utility/parastatal is not yet onboarded in a region, the report routes to the **Council** equivalent department as interim owner and is flagged for Admin.

### D.2 Taxonomy, routing, SLA, sensitivity & visibility

| Category | Example sub-categories | Default routing level | Default SLA (TTFR / TTR) | Sensitive? | Default visibility |
|---|---|---|---|---|---|
| **Water & Sanitation** | Broken/dry water point or borehole; pipe burst/leak; water quality/contamination; no piped supply; public toilet/latrine; sewage overflow; drainage blockage; solid-waste/garbage collection | **SECTOR/UTILITY** (DAWASA/RUWASA) → fallback **COUNCIL** Water dept | 48h / 14d | No | **PUBLIC** |
| **Roads & Transport** | Pothole/road damage; impassable/washed-out road; broken bridge/culvert; street lighting out; missing/broken signage; traffic signal fault; unsafe bus stand/stage; public-transport (daladala) safety | **SECTOR/UTILITY** (TARURA urban/rural; TANROADS trunk) → fallback **COUNCIL** Works | 72h / 30d | No | **PUBLIC** |
| **Electricity & Energy** | Power outage; exposed/fallen live wire *(safety)*; transformer fault; faulty/disputed meter; no connection in area; streetlight (if utility-owned) | **SECTOR/UTILITY** (TANESCO) | 24h / 14d (fallen-wire safety sub-cat: **4h / 48h**) | No | **PUBLIC** (disputed-meter sub-cat: **PRIVATE**) |
| **Health** | Clinic/dispensary out of stock (medicines); facility closed/no staff; poor service/negligence; disease outbreak/sanitation hazard; ambulance/referral failure; maternal/child health gap | **COUNCIL** Health dept (CHMT) → **DISTRICT/REGION** referral | 48h / 21d (outbreak sub-cat: **6h / 7d**) | **Partial** (negligence/complaint sub-cat **Sensitive**) | **PUBLIC** (individual complaint: **PRIVATE**) |
| **Education** | School infrastructure (classrooms, desks, toilets, water); teacher shortage/absence; missing materials; school-fee/levy dispute; safety on school route; misconduct *(sensitive)* | **COUNCIL** Education dept → **WARD** for facility-level | 72h / 30d | **Partial** (misconduct/abuse sub-cat **Sensitive**) | **PUBLIC** (misconduct: **PRIVATE**) |
| **Security & Safety** | Crime hotspot/insecurity; streetlight-related safety; mob justice risk; missing person; fire hazard; disaster/flood risk; dangerous structure | **WARD** + **OVERSIGHT** (Police/TPF) for crime | 6h / 7d (active-threat sub-cat: **1h** TTFR, immediate escalation) | **Partial** (crime/threat reports **Sensitive**) | **PRIVATE** by default (aggregate hotspots may be PUBLIC) |
| **Gender-Based Violence & Child Protection** | GBV; domestic violence; child abuse/neglect; trafficking; harassment | **OVERSIGHT** (GBV desk / Police gender-children desk / social welfare) | **2h** / per case-management protocol | **Sensitive (always)** | **PRIVATE (always)** — never PUBLIC |
| **Corruption & Governance** | Bribery/extortion (rushwa); embezzlement/misuse of public funds; abuse of office; procurement/ghost-project fraud; nepotism; service withheld pending bribe | **OVERSIGHT** (PCCB/TAKUKURU); governance complaints → **DISTRICT/REGION** integrity officer | **24h** ack / TTR per investigation (no public SLA promise) | **Sensitive (always)** | **PRIVATE (always)** |
| **Land & Housing** | Land/boundary dispute; double allocation; illegal occupation/eviction; title/survey delay; unsafe/illegal construction; demolition notice | **COUNCIL** Land dept → **DISTRICT** Land office; survey/title → **SECTOR** (Lands ministry/TBA) | 5d / 60d | **Partial** (eviction/dispute complaint **Sensitive**) | **PRIVATE** (individual disputes); zoning/illegal-construction may be PUBLIC |
| **Environment** | Pollution (air/water/noise); illegal dumping; deforestation/charcoal; quarry/mining harm; wetland/encroachment; industrial discharge | **SECTOR** (NEMC) → fallback **COUNCIL** Environment officer | 72h / 30d | **Partial** (whistleblowing on a firm **Sensitive**) | **PUBLIC** |
| **Agriculture & Livestock** | Extension-service gap; input/subsidy access; pest/disease (crop/animal) outbreak; market/storage; irrigation scheme; land-use/grazing conflict | **WARD** agric extension officer → **COUNCIL** Agriculture/Livestock dept | 5d / 30d (pest/disease outbreak: **24h / 7d**) | No | **PUBLIC** |
| **Social Welfare** | Vulnerable-person support (elderly, disability, orphans); TASAF/safety-net access; pension/benefit issue; disability-access barrier; emergency relief | **COUNCIL** Social Welfare → **WARD** social welfare officer | 5d / 30d | **Partial** (individual welfare case **Sensitive/PII**) | **PRIVATE** |
| **Public Services & Bureaucracy** | Birth/death certificate delay; ID/registration service; business-licence/permit; local-tax/levy issue; office closed/no staff; rude/poor service; long queues | **WARD** (WEO) → **COUNCIL** service desk; civil-registration → **SECTOR** (RITA) | 72h / 21d | **Partial** (staff-conduct complaint **Sensitive**) | **PUBLIC** (individual service complaint: **PRIVATE**) |
| **Community & Infrastructure** | Public market; cemetery/burial ground; recreation ground/playground; community hall; public Wi-Fi/digital point; abandoned/derelict public asset | **WARD** → **COUNCIL** Works | 5d / 45d | No | **PUBLIC** |
| **Other / Uncategorised** | Anything not matching above; multi-category; suggestion/feedback | **WARD** (default office) + **Admin triage** for re-categorisation | 72h / — (re-categorise within 72h) | No | **PRIVATE** until triaged |

> **SLA units & calendars.** TTFR = time from submission to first official action/acknowledgement on the case (status leaves `NEW`); TTR = time to `RESOLVED`. SLAs run on a configurable **business calendar** (per Council, with public-holiday awareness) except **safety/emergency** sub-categories, which run on a **24/7 wall-clock**. Breach of TTFR/TTR drives **UC-S01 → ESCALATED** (§12.1) and the *SLA breach* notifications (§13). Priority (`Report.priority`) can shorten effective SLA: an emergency-tagged report inherits the tightest SLA in its category tree.

### D.3 How routing rules are configurable

- **Per category × area-level matrix.** Admins (UC-B15) maintain a routing rule of the form `(category, areaLevel, area?) → responsibleScope`. Rules are **inherited down** the geography (a rule set at Region applies to all child wards unless overridden) and **most-specific-wins**, so a Council can override a national default for one ward.
- **Agency mapping & staged onboarding.** Each routing **token** (D.1) maps to live Area-Official scopes per area. Where a sector/utility is not yet onboarded (**D-Q5** region-by-region rollout), the rule resolves to the **interim Council fallback** and raises an Admin "unrouted-agency" flag — the software is launch-ready even before every agency is live.
- **Effective-dating & history.** Routing rules and SLA values are **effective-dated** (consistent with `WardConstituency`, §9.0) so reorganisations and onboarding milestones never rewrite the history of already-routed cases; a closed case retains the rule that governed it.
- **Round-robin / load-balancing.** When multiple equally-specific scopes match, the engine load-balances (UC-D04); ties and "no match" both alert Admin.
- **Reclassification.** Officials/Moderators may re-categorise a misfiled report; reclassification **re-runs routing** and **re-anchors the SLA clock** per the new category (logged as a `CaseEvent`, audited). "Other" must be triaged off within its 72h window.
- **Per-area SLA overrides.** A Council facing capacity constraints can lengthen (or a pilot region tighten) the default SLA for a category in its scope; overrides are versioned and surfaced to citizens as the *expected* SLA at submission time (UC-D01 step 7).

### D.4 Sensitive categories — stricter moderation & anonymity (per D-Q1, D16, §18)

Categories/sub-categories marked **Sensitive** (always: *GBV & Child Protection*, *Corruption & Governance*; partial: negligence/misconduct/abuse/dispute/whistleblowing sub-cases) carry a distinct handling profile:

- **Anonymity enabled.** Per **D-Q1**, these are the only categories where **fully anonymous** filing is permitted (no `reporterProfile` link persisted to responders; **T1** sufficient, identity not required). Non-sensitive categories require **T2** for official reports (Appendix A). The reporter may still *opt in* to attach identity for follow-up.
- **Visibility forced PRIVATE.** Sensitive reports default — and **GBV/Corruption are forced** — to `visibility = PRIVATE`; they never appear in public/near-me lists or the map (US-3.7), are excluded from upvote/follow, and are not indexed for public search (SY5). Only aggregate, de-identified statistics may surface publicly.
- **Restricted access scope.** Visible only to the specifically-routed **oversight scope** + Moderators, not the general Area-Official queue. Internal notes only; **no public CaseEvent** thread is shown to a wider audience.
- **Stricter moderation gate.** All sensitive reports enter a **moderation/triage hold** *before* routing (hybrid pipeline, **D-Q8**): auto-assist screens for PII leakage, doxxing, and false-accusation patterns; a Moderator confirms classification and strips/encrypts identifying details before the case reaches responders. Swahili-aware filters apply.
- **Tighter anti-abuse.** Lower rate limits and stronger sockpuppet/duplicate detection (§18) to deter coordinated false reports; corruption reports are not given a public TTR promise (handled per-investigation) to avoid implying guilt.
- **Conflict guardrails (D16).** Routing **excludes** any Area Official who is the **subject** of a corruption/conduct report; such cases escalate one administrative level up (or to independent oversight) automatically, and the exclusion is audited.
- **PII protection (§18, PDPA).** Reporter and victim PII on sensitive cases is **field-encrypted at rest**, redacted from logs, access-logged, and subject to **shorter retention**; erasure requests (UC-A17 / UC-S09) are honoured without breaking case integrity (de-identification rather than deletion of the case record).
- **Safe-routing for GBV.** GBV/child-protection cases bypass the standard ward queue entirely, route to the designated **protection desk/social-welfare** scope on a **2h** wall-clock, and surface crisis-referral information (helpline) to the reporter in-flow rather than implying a self-service resolution timeline.

*(This appendix is the seed for `IssueCategory` reference data — Appendix B — and is maintained as a living taxonomy via the admin console alongside the codebase.)*

---

## Appendix E — Analytics Event Taxonomy & Measurement Plan

> **Purpose.** Defines the canonical product-analytics events emitted by the platform (via SY7 *Analytics/Event Pipeline*, UC-S07) that power the **KPIs in §3.3** and the **dashboards in Appendix C**. Events are emitted from domain events on the **transactional outbox** (same source as feed/notification fan-out, §16) so analytics, audit, and operational state never diverge. This is a measurement contract, **not** the audit log (§18) — analytics carries **no PII** (see E.4).

### E.0 Conventions

- **Naming:** `noun_verb_pastTense`, `snake_case`, stable forever (additive evolution only; never repurpose a name). Versioned via `schema_version`.
- **Identity:** every event carries a pseudonymous `actor_ref` (salted hash of the account UUID — **not** the UUID, name, phone, or ID) and `anon_id` for pre-auth/guest events; the two are stitched on `account_signed_up`.
- **Standard envelope (on every event):** `event_id` (idempotency), `event_name`, `schema_version`, `occurred_at` (server UTC), `actor_ref`/`anon_id`, `active_role` (the §6.4 "hat": CITIZEN/REPRESENTATIVE/AREA_OFFICIAL/MODERATOR/ADMIN/ROOT), `trust_tier` (T0–T3), `channel` (APP/WEB/PWA/USSD/SMS/ADMIN/API), `app_version`, `locale` (sw/en).
- **Standard geo dimensions (aggregate only, see E.4):** `region_code`, `council_code` (LGA/Halmashauri), `ward_code`, `constituency_code`. Pinned below ward (village/hamlet) is **never** sent to analytics.
- **Standard taxonomy dimensions where applicable:** `category_code` (issue category), `rep_type` (MP/COUNCILLOR/WARD_EXEC), `mandate` (CONSTITUENCY/SPECIAL_SEATS/NOMINATED/COUNCILLOR_WARD).
- **Object refs:** opaque hashed surrogate ids (`report_ref`, `petition_ref`, `rep_ref`, `announcement_ref`, …) — joinable across events, not resolvable to PII.

### E.1 Event catalogue

Standard envelope/geo/taxonomy fields are implied on every row; **Key properties** lists only event-specific additions. KPI codes refer to §3.3 themes (**Adoption, Verification, Reporting, Resolution, Responsiveness, Engagement, Reach, Trust, Reliability**).

#### Identity, onboarding & verification (M0)

| Event name | Trigger | Key properties | Feeds KPI(s) |
|---|---|---|---|
| `account_signed_up` | OTP confirmed, account created at T1 (US-0.1) | `signup_channel` {phone/email/ussd}, `referrer`, stitches `anon_id`→`actor_ref` | Adoption (MAU base); **Signup funnel** entry |
| `profile_completed` | Required fields + primary location set, reaches T2 (US-0.2) | `from_tier`=T1, `to_tier`=T2, `fields_completed`, `primary_set` (bool) | Verification funnel (T1→T2) |
| `identity_verification_started` | Citizen submits idType+idNo/evidence (US-0.3, UC-A07/08) | `id_type` {NATIONAL/VOTER}, `method` {AUTO/OPERATOR} | Verification funnel; Trust |
| `identity_verified` | T3 reached (provider success or moderator approval) | `from_tier`=T2, `to_tier`=T3, `method`, `latency_to_verify_s`, `electoral_location_set` (bool, set when VOTER) | **Verification (% ID-verified ≥40%)**; T2→T3 funnel |
| `identity_verification_failed` | Provider reject / dedup block / moderator reject | `reason` {DUPLICATE_ID/NO_MATCH/EVIDENCE/EXPIRED}, `method` | Verification (drop-off); Trust |
| `account_recovered` | Password reset / OTP recovery completed (US-0.4) | `method` | Adoption (retention) |
| `location_added` | New `ProfileLocation` created (US-0.8, UC-A25) | `association_type` {HOME/RESIDENCE/WORK/FAMILY/…}, geo dims | Reach (geo participation) |
| `primary_location_set` | `isPrimary` moved (UC-A26) | geo dims | Reach |
| `electoral_location_set` | `isElectoral` set/changed (UC-A27) | `source` {VOTER_ID/MANUAL}, `cooldown_enforced` (bool) | Trust (integrity); Verification |
| `role_granted` | Admin attaches REPRESENTATIVE/AREA_OFFICIAL/etc. to existing account (US-0.6, UC-A28) | `granted_role`, `rep_type`, `mandate`, `scope_level`, `same_account` (bool) | Adoption (responder coverage); program tracking |
| `role_transitioned` | Role status change e.g. SITTING→FORMER (UC-A31) | `granted_role`, `from_status`, `to_status` | Responder coverage |
| `context_switched` | Multi-role user switches active hat (US-0.9, UC-A30) | `from_role`, `to_role` | Engagement (multi-hat usage) |
| `preferences_updated` | Notification/language/privacy/per-location prefs changed (US-0.7) | `changed` {channels/quiet_hours/locale/consent}, `consent_state` | Reach; Trust (consent) |
| `organisation_registered` / `organisation_verified` | Org sign-up / moderator approval (US-0.5) | `org_type`, `verify_method` | Adoption (orgs); Engagement enablement |

#### Representatives & institutions (M2/M6/M7)

| Event name | Trigger | Key properties | Feeds KPI(s) |
|---|---|---|---|
| `rep_searched` | "Find my rep" executed (US-2.1, UC-C01) | `search_method` {GPS/WARD/CONSTITUENCY}, `result_count` | Adoption (transparency O2); engagement |
| `rep_profile_viewed` | Representative profile opened (US-2.2) | `rep_ref`, `rep_type`, `from_surface` | Engagement; Adoption |
| `rep_followed` / `rep_unfollowed` | Follow toggled (UC-C03) | `rep_ref`, `rep_type` | Engagement (follows trend up) |
| `rep_profile_updated` | Representative edits own profile (US-2.3, moderated) | `fields_changed`, `moderation_required` (bool) | Trust |
| `rep_rated` | T3 citizen submits rating (US-6.2, UC-F05) | `rep_ref`, `rep_type`, `score`, `period`, `from_electoral_location` (bool) | Engagement; **Responsiveness/accountability**; Trust (integrity) |
| `contribution_published` | Contribution added (US-6.1, UC-F01) | `rep_ref`, `contribution_type` {SPEECH/MOTION/BILL/VOTE/…}, `author_role` | Accountability coverage (Appendix C) |
| `promise_status_changed` | Promise/pledge status transition (US-6.3) | `rep_ref`, `from_status`, `to_status` | Accountability |
| `project_published` | Project created (US-7.2, UC-F07) | `project_ref`, `sector`, `funding_source` (e.g. CDF), `status`, geo dims | Project delivery (Appendix C) |
| `project_status_changed` | Project status/progress update (US-7.2, UC-F08) | `project_ref`, `from_status`, `to_status`, `progress_pct` | Project delivery/progress |
| `project_followed` / `project_unfollowed` | Follow toggled (UC-F10) | `project_ref`, `sector` | Engagement (follows) |

#### Issue reporting & case management (M3)

| Event name | Trigger | Key properties | Feeds KPI(s) |
|---|---|---|---|
| `report_started` | Report flow opened (UC-D01) | `entry_point` | Reporting (funnel top; abandonment) |
| `report_filed` | Report successfully submitted, ticket issued (US-3.1, UC-D01/D02) | `report_ref`, `category_code`, `visibility` {PUBLIC/PRIVATE}, `trust_mode` {OFFICIAL/COMMUNITY/ANON}, `attachment_count`, `location_source` {GPS/MANUAL}, `is_offline_sync` (bool), geo dims | **Reporting (reports/month)**; Reach (channel mix); Trust |
| `report_routed` | System auto-routes to responsible office (UC-D04) | `report_ref`, `routed_office_ref`, `routed_level` {COUNCIL/WARD/…}, `route_matched` (bool), `category_code` | Reporting; ops health (Appendix C routing) |
| `report_first_responded` | First official action/note after filing (US-3.4) | `report_ref`, `time_to_first_response_s` | **Reporting (median TTFR < 48h)**; Responsiveness |
| `report_assigned` | Case assigned/reassigned to official (UC-D08) | `report_ref`, `assignee_ref`, `is_reassignment` (bool) | Resolution (workload); SLA |
| `report_status_changed` | Any case state transition (§12.1, UC-D09) | `report_ref`, `from_status`, `to_status`, `note_type` {PUBLIC/INTERNAL}, `actor_role`, `category_code`, geo dims | Resolution; SLA; **state-machine analytics** |
| `report_resolved` | Status set RESOLVED with resolution note (US-3.4, UC-D11) | `report_ref`, `time_to_resolution_s`, `has_proof` (bool), `reopen_count` | **Resolution (% resolved ≥60%; median TTR <30d)** |
| `report_confirmed` | Citizen confirms resolution → CLOSED (US-3.5, UC-D12) | `report_ref`, `outcome`=CONFIRMED, `confirm_latency_s` | **Resolution (confirmation rate)**; Trust (loop closure) |
| `report_disputed` | Citizen disputes → REOPENED/ESCALATED (UC-D12) | `report_ref`, `outcome`=DISPUTED | Resolution (reopen/dispute rate) |
| `report_auto_closed` | Auto-close after confirmation window (UC-D13) | `report_ref`, `window_days` | Resolution (auto vs confirmed split) |
| `report_escalated` | SLA breach or manual escalation (US-3.6, UC-D14) | `report_ref`, `escalation_reason` {SLA_BREACH/MANUAL}, `to_level`, `breach_type` {TTFR/TTR} | **SLA breach heatmap** (Appendix C); Resolution |
| `report_upvoted` / `report_followed` | T1+ upvote/follow public report (US-3.7, UC-D16) | `report_ref`, `category_code`, geo dims | Engagement; Reporting (issue weighting) |
| `report_duplicate_merged` | Official/moderator merges duplicate (UC-D15) | `report_ref`, `merged_into_ref` | Ops quality |
| `report_withdrawn` | Reporter withdraws own report (UC-D19) | `report_ref`, `reason` | Reporting (quality) |

#### Engagement — petitions, surveys/polls, Q&A, comments (M8–M11)

| Event name | Trigger | Key properties | Feeds KPI(s) |
|---|---|---|---|
| `petition_created` | Petition created (US-9.1, UC-E01) | `petition_ref`, `target_type` {REP/OFFICE}, `signature_goal`, `deadline_set` (bool) | Engagement |
| `petition_signed` | T3 citizen signs once (US-9.2, UC-E03) | `petition_ref`, `from_electoral_location` (bool), `comment_attached` (bool), `is_public` (bool) | **Engagement (signatures)**; Trust (integrity) |
| `petition_threshold_reached` | Signature goal hit (UC-E04) | `petition_ref`, `signatures`, `time_to_threshold_s` | Engagement (effectiveness) |
| `petition_responded` | Target publishes response (US-9.3, UC-E05) | `petition_ref`, `responder_role`, `response_latency_s` | **Responsiveness**; Engagement |
| `survey_published` | Survey/poll opened (US-8.1, UC-E06) | `survey_ref`, `survey_type` {SURVEY/POLL}, `binding` (bool), `audience_scope` | Engagement (enablement) |
| `survey_responded` | Citizen responds (US-8.2, UC-E07) | `survey_ref`, `survey_type`, `binding` (bool), `completed` (bool) | **Engagement (poll responses/active user)** |
| `survey_results_viewed` | Aggregate results opened (UC-E08) | `survey_ref` | Engagement |
| `question_asked` | T2 citizen asks rep a question (US-10.1, UC-E09) | `question_ref`, `rep_ref`, `rep_type` | **Responsiveness (Q&A asked)**; Engagement |
| `question_answered` | Rep answers (US-10.2, UC-E10) | `question_ref`, `rep_ref`, `answer_latency_s`, `within_14d` (bool) | **Responsiveness (% Q&A answered ≤14d ≥50%)** |
| `question_declined` | Rep declines with reason (UC-E10) | `question_ref`, `rep_ref` | Responsiveness (decline rate) |
| `question_upvoted` | Upvote to prioritise (UC-E11) | `question_ref` | Engagement |
| `comment_posted` | T2 comment on report/announcement/project (US-11.1, UC-E12) | `host_type`, `host_ref`, `thread_depth` | Engagement |

#### Announcements, feed & notifications (M4/M5)

| Event name | Trigger | Key properties | Feeds KPI(s) |
|---|---|---|---|
| `announcement_published` | Official/rep/org publishes (US-4.1, UC-G01) | `announcement_ref`, `author_role`, `audience_scope`, `channels[]` {FEED/PUSH/SMS}, `scheduled` (bool), geo dims | Communications reach; **Responsiveness** |
| `feed_item_viewed` | Feed item rendered/opened (US-4.2, UC-G04) | `item_type` {ANNOUNCEMENT/REPORT/PROJECT}, `host_ref`, `feed_position` | Engagement; Adoption (DAU/MAU) |
| `feed_item_engaged` | Tap-through / share / save on a feed item | `item_type`, `action` {OPEN/SHARE/SAVE} | Engagement |
| `subscription_changed` | Follow/unfollow area/category/rep/project (US-4.3, UC-G05) | `target_type`, `target_ref`, `action` {FOLLOW/UNFOLLOW} | Engagement (follows) |
| `notification_dispatched` | Notification queued/sent by SY1 (US-5.2, UC-S03) | `notif_ref`, `notif_type`, `notif_channel` {PUSH/SMS/EMAIL/FEED}, `language`, `is_fallback` (bool) | **Reliability** (delivery); Reach (channel mix) |
| `notification_delivered` | Provider delivery receipt | `notif_ref`, `notif_channel`, `delivery_latency_s` | **Reliability (delivery rates)** |
| `notification_failed` | Delivery failed after retries | `notif_ref`, `notif_channel`, `failure_reason`, `retry_count` | Reliability (failure rate) |
| `notification_opened` | Push/feed notification opened | `notif_ref`, `notif_channel`, `notif_type` | Engagement (CTR) |

#### Moderation, trust & safety (M12)

| Event name | Trigger | Key properties | Feeds KPI(s) |
|---|---|---|---|
| `content_flagged` | User flags content/abuse (US-12.1, UC-E13/H01) | `host_type`, `host_ref`, `flag_reason` | **Trust (abuse report rate <2%)** |
| `moderation_action_taken` | Moderator acts (US-12.2, UC-H02) | `host_type`, `host_ref`, `action` {APPROVE/HIDE/REMOVE/WARN/SUSPEND/VERIFY}, `was_auto_assisted` (bool), `action_latency_s` | **Trust (% content moderated)**; Reliability (queue) |
| `moderation_appeal_resolved` | Appeal decided (UC-H03) | `host_ref`, `outcome` {UPHELD/OVERTURNED} | Trust (fairness) |
| `auto_moderation_triaged` | Auto-assist auto-flags/holds (US-12.3, UC-H05) | `host_type`, `signal` {PROFANITY/PII/SPAM/IMAGE}, `confidence`, `held` (bool) | Trust (auto vs manual split) |

#### Channel & session (cross-cutting — Reach)

| Event name | Trigger | Key properties | Feeds KPI(s) |
|---|---|---|---|
| `session_started` | App/web/PWA session opened; USSD session initiated | `channel`, `network_type`, `low_data_mode` (bool), geo dims | **Reach (% sessions via USSD/SMS; % Swahili)**; Adoption (MAU/DAU) |
| `ussd_session_completed` | USSD menu flow ends (UC-D02/D09) | `flow` {REPORT/STATUS/OTP}, `completed` (bool), `steps`, `duration_s` | Reach (feature-phone usability) |
| `language_selected` | Locale chosen/changed | `from_locale`, `to_locale` | **Reach (% Swahili usage)** |
| `search_performed` | Public/entity search executed (UC-B09, SY5) | `search_type` {LOCATION/REP/REPORT/PROJECT}, `result_count`, `zero_results` (bool) | Adoption; search quality |

### E.2 Measurement plan — funnels, segments & core metrics

**Primary funnels** (segment every step by `region_code`/`council_code`/`constituency_code`, `channel`, `locale`, `category_code` where relevant):

1. **Identity / verification funnel** — `account_signed_up` (T1) → `profile_completed` (T2) → `identity_verification_started` → `identity_verified` (T3). Headline conversion = **% active citizens at T3** (§3.3 Verification ≥40%); watch T2→T3 drop-off and `identity_verification_failed.reason=DUPLICATE_ID` (D15 dedup pressure).
2. **Report-to-confirmation loop** — `report_filed` → `report_routed` (`route_matched`) → `report_first_responded` (**TTFR**) → `report_resolved` (**TTR**, % resolved) → `report_confirmed` / `report_disputed` / `report_auto_closed` (**confirmation rate**, the "close-the-loop" KPI O3). Reopen rate from `report_resolved.reopen_count` + `report_disputed`. This funnel is the principal lens for the D5 region-by-region onboarding rollout — track it per `council_code` so the loop is proven where officials are live.
3. **Report-flow abandonment** — `report_started` → `report_filed` (drop-off by `channel`; USSD vs app via `ussd_session_completed`).
4. **Engagement conversion** — exposure (`feed_item_viewed` / `announcement_published`) → action (`petition_signed`, `survey_responded`, `question_asked`, `rep_rated`, `subscription_changed`). Q&A responsiveness sub-funnel: `question_asked` → `question_answered.within_14d` (§3.3 ≥50%).

**Engagement metrics:** DAU/WAU/MAU from `session_started` + stickiness (DAU/MAU); actions-per-active-user (signatures, poll responses, follows — §3.3 "trend up"); content depth (`comment_posted`, `feed_item_engaged`); follow graph growth (`subscription_changed`, `rep_followed`, `project_followed`). Cohort retention keyed on `account_signed_up` and on `identity_verified` (verified users are the high-value cohort).

**Channel mix (Reach):** distribution of `session_started.channel` and `report_filed.channel`; **% sessions via USSD/SMS** and **% Swahili** (`locale`/`language_selected`); `notification_dispatched`/`_delivered` split by `notif_channel` and `is_fallback` (SMS-as-fallback rate). Powers Appendix C "channel mix / language usage".

**Responsiveness & SLA (ops):** TTFR/TTR distributions (p50/p90) from `report_first_responded` / `report_resolved`; **SLA breach heatmap** from `report_escalated.breach_type` × geo × `category_code`; representative responsiveness from `question_answered.answer_latency_s` and `petition_responded.response_latency_s`. These mirror the Appendix C dashboards and the §13 notification triggers.

**Trust & safety:** abuse rate = `content_flagged` / total content created (target <2%); `moderation_action_taken` volume and `action_latency_s` (queue health); auto-vs-manual split from `auto_moderation_triaged.held`; integrity signals from `electoral_location_set.cooldown_enforced` and binding-action `from_electoral_location` flags (D13 anti-double-influence).

**Reliability:** notification delivery rate (`notification_delivered`/`notification_dispatched`), failure rate by channel; complements §15 service-level observability (this is product delivery health, distinct from API p95/availability which come from infra metrics, not this pipeline).

### E.3 Implementation notes

- **Emission:** events derive from the same **domain events / transactional outbox** that drive feed fan-out and notifications (§16), guaranteeing one source of truth and **idempotency** via `event_id`. A thin client SDK (Flutter/Web/Angular) emits UI-only events (`*_viewed`, `*_started`, `session_started`); all state-changing events are emitted **server-side** so they cannot be spoofed or dropped by the client.
- **Schema governance:** every event has a registered schema (`schema_version`); changes are **additive only**; a CI contract test fails the build if a payload omits a required envelope field or an event referenced by a KPI dashboard is removed.
- **Late/offline events:** offline-drafted reports (US-3.1) emit `report_filed` with `is_offline_sync=true` and the true `occurred_at`; the pipeline keys on `occurred_at`, not ingest time, so funnels are not skewed.

### E.4 Privacy notes (binding — see §18 & §15)

- **No PII in analytics, ever.** No names, phone/MSISDN, email, **national/voter ID**, free-text report/comment bodies, photos, or precise GPS. Actor identity is a **salted pseudonymous hash** (`actor_ref`); a separate key allows GDPR/Tanzania PDPA **erasure** (UC-A17/UC-S09) to delete the mapping and orphan the analytics rows.
- **Aggregate geo only.** Geography is truncated to **ward level at most** (`ward_code`); village/hamlet/Mtaa and raw GPS are **never** emitted. Dashboards apply **k-anonymity (suppress cells with n < 10)** so a ward+category+tier slice can't re-identify an individual reporter — important for sensitive categories (corruption/GBV, D-Q1).
- **`ProfileLocation` is private PII (§9.0)** and is never surfaced in analytics beyond the aggregated geo codes above; `isElectoral`/`isPrimary` changes are tracked only as boolean/`source` flags, not as locations.
- **Consent-aware:** analytics respects the user's data-sharing consent (`preferences_updated.consent_state`); opted-out users still emit the **minimum operational events** needed for SLA/safety (e.g. `report_routed`, `moderation_action_taken`) but are excluded from behavioural/engagement analytics.
- **Separation of duties:** this pipeline is **not** the audit log. Security/compliance auditing (who-did-what with full attribution) lives in the immutable `AuditLog` (§18); analytics is pseudonymous and aggregate. The two never share storage or access controls.

---

## Appendix F — Full RBAC Permission Matrix

**Legend:** ✅ allowed · 🔶 own/scoped only (see footnotes) · ➖ not allowed · **(T#)** trust-tier gated (§7.3). Columns split Citizen by tier where it matters. **Org** = Organisation member/admin (admin-only rows noted in footnotes). **Area Official**, **Representative** are **scope-bound** (areas/categories, or own constituency/ward & own profile). Staff roles may use citizen features under the **conflict-of-interest rules** (footnotes ⁱ–ᵛ). All ✅/🔶 state-changing staff/official/moderation actions are **audited** (§15, D16).

> Citizen columns: **T1** = registered, **T2** = profiled, **T3** = ID-verified. A cell like `✅(T3)` means the action requires that tier.

### A.1 Authentication, Profile & Locations
| Action | Guest | Citizen T1 | Citizen T2 | Citizen T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| Sign up (phone/email + OTP) → T1 | ✅ | — | — | — | — | — | — | — | — | — |
| Log in / refresh / log out | ➖ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Reset/change password; enable MFA | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| Complete profile → T2 | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| Verify national/voter ID → T3 | ➖ | 🔶 | 🔶 | — | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| Add/remove own location | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| Set **primary** location | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| Set **electoral** location (manual) | ➖ | ➖ | 🔶ᵃ | 🔶ᵃ | — | 🔶ᵃ | 🔶ᵃ | 🔶ᵃ | 🔶ᵃ | 🔶ᵃ |
| Manage prefs/language/privacy (per-location) | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| Manage consent / data-sharing | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| Deactivate/delete own account (erasure) | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶ᵇ | 🔶ᵇ | 🔶ᵇ | 🔶ᵇ | 🔶ᵇ |
| Switch active role/context | ➖ | — | — | — | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

### A.2 Geography & Reference-Data Administration
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| Browse/search locations; reverse-geocode | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CRUD Region→Hamlet, Council/LGA, Constituency | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| Bulk import geography / seed | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| Manage `WardConstituency` mapping (effective-dated) | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| CRUD Party / Parliament / Parliament role | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| CRUD Issue category | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| Configure SLA & routing rules (category × area) | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |

### A.3 Reports & Cases (full lifecycle)
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| File **community** report (lower-trust) | ➖ | ✅ | ✅ | ✅ | ✅ | ✅ⁱ | ✅ⁱ | ✅ⁱ | ✅ⁱ | ✅ⁱ |
| File **official** report | ➖ | ➖ | ✅(T2) | ✅ | ✅ | ✅ⁱ | ✅ⁱ | ✅ⁱ | ✅ⁱ | ✅ⁱ |
| File **anonymous** report (sensitive cats only) | ✅ᶜ | ✅ᶜ | ✅ᶜ | ✅ᶜ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ |
| Save offline draft & sync | ➖ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Track own report / add info to own report | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| Withdraw own report | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| View public reports (map/list) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Upvote / follow public report | ➖ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| View **official triage queue** | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(scope) | ➖ | ✅ | ✅ |
| Assign / reassign case | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(scope) | ➖ | ✅ | ✅ |
| Change status / add internal note / request info | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(scope)ⁱⁱ | ➖ | ✅ | ✅ |
| Add **public** note to a case | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(scope) | 🔶 | ✅ | ✅ |
| Resolve case | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(scope)ⁱⁱ | ➖ | ✅ | ✅ |
| Confirm / dispute resolution (on own report) | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| Reopen case | ➖ | 🔶(own) | 🔶(own) | 🔶(own) | 🔶(own) | ➖ | 🔶(scope) | ➖ | ✅ | ✅ |
| Escalate case (manual) | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(scope) | ➖ | ✅ | ✅ |
| Merge duplicates | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(scope) | ✅ | ✅ | ✅ |
| Hide/remove abusive report | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ | ✅ |
| Export case report | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(scope) | ➖ | ✅ | ✅ |

### A.4 Representatives & Institutions
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| Find my representatives / view rep profile | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| List/search reps; view party/parliament directory | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Follow / unfollow representative | ➖ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Edit own rep public profile (moderated) | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(own)ⁱⁱⁱ | ➖ | ➖ | ✅ | ✅ |
| Admin create/link representative; grant REP role | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| Transition rep SITTING→FORMER (term end) | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |

### A.5 Contributions, Projects, Promises & Ratings (Accountability)
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| View contributions / promises / ratings | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Publish/edit rep contribution | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(own)ⁱⁱⁱ | ➖ | ➖ | ✅ | ✅ |
| Record attendance | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| Create/track promise (pledge) | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(own)ⁱⁱⁱ | ➖ | ➖ | ✅ | ✅ |
| View projects by area; follow project | ✅ⁿ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Publish/update project (status/progress) | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(own/authorised) | 🔶(scope) | ➖ | ✅ | ✅ |
| Link a report to a project | ➖ | 🔶(own report) | 🔶(own report) | 🔶(own report) | 🔶 | 🔶(own) | 🔶(scope) | ✅ | ✅ | ✅ |
| **Rate representative** | ➖ | ➖ | ➖ | ✅(T3)ⁱᵛ | ➖ | ➖ⁱᵛ | ➖ | ➖ | ➖ | ➖ |
| Edit/delete a rating | ➖ | 🔶(own) | 🔶(own) | 🔶(own) | ➖ | ➖ⁱᵛ | ➖ | ➖ | ➖ | ➖ |

### A.6 Petitions
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| View petitions | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Create petition | ➖ | ➖ | ➖ | ✅(T3) | ✅(admin) | ➖ⁱᵛ | ➖ | ➖ | ✅ | ✅ |
| **Sign petition** | ➖ | ➖ | ➖ | ✅(T3)ᵃ | ➖ | ✅(T3)ⁱᵛ | ✅(T3)ⁱ | ✅(T3)ⁱ | ✅(T3)ⁱ | ✅(T3)ⁱ |
| Respond to petition (as target) | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(targeted)ⁱⁱⁱ | 🔶(targeted) | ➖ | ✅ | ✅ |
| Moderate petition before publish | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ | ✅ |

### A.7 Surveys & Polls
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| View survey/poll & permitted results | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Create survey/poll | ➖ | ➖ | ➖ | ➖ | ✅(admin) | 🔶(own scope) | 🔶(scope) | ➖ | ✅ | ✅ |
| Respond to **non-binding** poll/survey | ➖ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Respond to **binding** poll | ➖ | ➖ | ➖ | ✅(T3)ᵃ | ➖ | ✅(T3)ⁱ | ✅(T3)ⁱ | ✅(T3)ⁱ | ✅(T3)ⁱ | ✅(T3)ⁱ |

### A.8 Q&A to Representatives
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| View Q&A | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Ask question to a rep | ➖ | ➖ | ✅(T2) | ✅ | ✅ | ✅ⁱ | ✅ⁱ | ✅ⁱ | ✅ⁱ | ✅ⁱ |
| Upvote a question | ➖ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Answer / decline a question | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(targeting-self only)ⁱⁱⁱ | ➖ | ➖ | ✅ | ✅ |

### A.9 Comments & Discussions
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| Read comments | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Post comment (moderated) | ➖ | ➖ | ✅(T2) | ✅ | ✅ | ✅ⁱ | ✅ⁱ | ✅ⁱ | ✅ⁱ | ✅ⁱ |
| Edit/delete own comment (edit window) | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |
| Hide/remove others' comments | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ⁱⁱ | ✅ | ✅ |

### A.10 Announcements, Feed & Notifications
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| View published announcements | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Personalised feed | ➖ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Follow area/category/rep/project; manage subs | ➖ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Publish announcement | ➖ | ➖ | ➖ | ➖ | 🔶(verified org, scope)ⁱⁱⁱ | 🔶(own constituency/ward)ⁱⁱⁱ | 🔶(scope)ⁱⁱⁱ | ➖ | ✅ | ✅ |
| Schedule/expire announcement | ➖ | ➖ | ➖ | ➖ | 🔶(own) | 🔶(own) | 🔶(own) | ➖ | ✅ | ✅ |
| Moderate announcement (new/untrusted author) | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ | ✅ |
| Manage own notification prefs; mark read | ➖ | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 | 🔶 |

### A.11 Moderation, Verification & Trust
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| Flag content / report abuse | ➖ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Review flag queue; take moderation action | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ⁱⁱ | ✅ | ✅ |
| Warn / suspend a user account | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶ⁱⁱ | ✅ | ✅ |
| Review **ID verification** queue (operator-assisted) | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ⁱⁱ | ✅ | ✅ |
| Review **rep-claim / org** verification | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ | ✅ |
| Handle appeal | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶ᵉ | ✅ | ✅ |
| Configure auto-moderation thresholds | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |

### A.12 Organisations & Campaigns
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| Register organisation | ➖ | ➖ | ➖ | ✅(T3) | — | ➖ | ➖ | ➖ | ✅ | ✅ |
| Verify organisation | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ | ✅ |
| Invite/manage org members & roles | ➖ | ➖ | ➖ | ➖ | 🔶(org admin) | ➖ | ➖ | ➖ | ✅ | ✅ |
| Run campaign (aggregate petitions/surveys) | ➖ | ➖ | ➖ | ➖ | 🔶(org, P3) | ➖ | ➖ | ➖ | ✅ | ✅ |

### A.13 Configuration, Admin & System
| Action | Guest | Citizen T1 | T2 | T3 | Org | Rep | Area Official | Moderator | Admin | Root |
|---|---|---|---|---|---|---|---|---|---|---|
| Onboard Area Official (assign area/category scope) | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| Grant/revoke roles; suspend/reinstate accounts | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| Detect/merge duplicate accounts (ID dedup) | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶ⁱⁱ | ✅ | ✅ |
| Admin dashboards & analytics | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(scope) | 🔶(mod metrics) | ✅ | ✅ |
| View audit log | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶(mod scope) | ✅ | ✅ |
| Configure feature flags / mobile app config | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| Bulk operations (import/export/taxonomy) | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ |
| Trigger data-export / erasure job (subject request) | ➖ | 🔶(own) | 🔶(own) | 🔶(own) | 🔶(own) | 🔶(own) | ➖ | ➖ | ✅ | ✅ |
| **Manage Admins / system security settings** | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 🔶ᶠ | ✅ |
| Irreversible/system-level operations | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ |

### Footnotes — conflict-of-interest & scope rules (D13 / D16)
A staff or representative account may use ordinary citizen features (it still holds the **Citizen** role, §6.4), but **no actor may act on themselves or on their own work**, and every multi-hat action is **audited**.

- **ⁱ — Staff-as-citizen.** Officials, Moderators, Admins and Root may file reports, ask questions, comment, sign petitions, and vote in their own civic capacity (T3-gated where applicable), acting under the **Citizen** context — never to influence work they administer. Action context is recorded.
- **ⁱⁱ — Self-work exclusion.** An **Area Official** may not triage/assign/resolve/escalate a report **they filed**; a **Moderator** may not moderate, suspend, dedup, verify, or rule on an appeal for **their own content/account or a case they are party to**. Such items route to another official/moderator.
- **ⁱⁱⁱ — Own-profile/own-scope only.** A **Representative** edits only **their own** profile/contributions/promises, answers only questions **targeting themselves**, responds only to petitions **targeting them**, and publishes announcements/projects only for **their own constituency/ward**. An **Area Official**/**Org** acts only within assigned area/category scope (or own verified org). Identity/verified fields are immutable to the holder; changes are moderated and audited.
- **ⁱᵛ — No self-accountability.** A **Representative** may **not rate, sign a petition against, create a petition targeting, or answer a question about themselves**, and may not vote in a binding poll concerning their own mandate. Rating a representative is a **T3 citizen-only** action; Admin/Root cannot rate (no privileged influence on accountability scores).
- **ᵛ — Audit-all.** Every ✅/🔶 state-changing action by Area Official, Representative, Moderator, Admin or Root is written to the immutable **AuditLog** with actor, active context/role, scope, and before/after state (§15, §18).
- **ᵃ — Electoral-location gating.** Binding civic actions (**rate MP, sign a constituency petition, vote in a binding poll**) are scoped to the citizen's single **`isElectoral`** location (§9.0/D13). `isElectoral` is **voter-ID-authoritative**; manual change requires T2+ and is **rate-limited (cooldown) + audited** to prevent double-influence.
- **ᵇ — Active-role erasure constraint.** A holder of an active staff/representative role cannot self-delete the account while the role is active; the role must first be revoked/transitioned (e.g. Rep→FORMER) by an Admin, preserving civic history; PII is then erased/anonymised (UC-A17/UC-S09).
- **ᶜ — Anonymous reporting.** Permitted only for **sensitive categories** (corruption, GBV) per **D-Q1**, with stricter moderation and rate limits; no reporter identity is stored/surfaced; not a general reporting mode.
- **ᵉ — Appeal independence.** Appeals are handled by a **different** moderator than the one who took the original action (and never by a party to the content); contested appeals escalate to Admin.
- **ᶠ — Admin vs Root.** Admins may manage **other Admins/officials/moderators and most config**, but **system-security settings, Root management, and irreversible/system-level operations are Root-only**.
- **ⁿ — Project visibility.** Projects are public (M7, Phase 2); Guests can view and browse but cannot follow (follow requires T1+).
