# Taarifu — Tanzania Civic-Readiness Review (As Built)

> **Reviewer:** Mzee Salehe Kombo — Tanzanian Civic & Governance Domain Expert
> **Date:** 2026-06-24 · **Scope:** civic correctness of the platform **as built** —
> seed migrations **V70–V83**, plus the geography/institutions/reporting schema
> (**V20–V29**) and the geography Java module. **DOCS ONLY** (no code touched).
> **Method:** grounded in `PRD.md` (§6.4, §9.0/§9.1, §24, §25, Appendix D, §19/§25.10
> decisions D6/D13/D14/D17/D20/D21, F1–F3 seed-correctness notes); live-fact checks
> against press/encyclopaedic sources where noted; anything I could not confirm is
> marked **UNVERIFIED**.

---

## 0. Bottom line

The **foundation is civically sound and respectful**. The administrative hierarchy,
the Swahili civic vocabulary, the electoral-vs-residence split, the
constituency-overlay model, the sensitive-category protections, and the routing-token
abstraction are all modelled the way Tanzania actually governs itself. The seed authors
were careful and honest — they flagged their own coverage gaps (`UNVERIFIED`,
`PARTIAL`, "enrichment gap") rather than fabricating data. That is exactly the discipline
this domain needs.

**But what is *built* is a reference-data substrate and schema — not yet a working
civic product.** The routing engine, responder directory, and the representatives module
are **schema + deferred stubs only**; no `responder`/`routing_rule` tables exist, and
`report.assigned_responder_id` is an explicit STUB (routing DEFERRED). So today a citizen
report cannot actually reach an office. That is a known, correct staging decision (D-Q5),
but it must be stated plainly: **the close-the-loop promise is not yet real.**

Severity legend: **P1** = blocks correct civic behaviour / political or legal exposure ·
**P2** = wrong-but-contained or freshness risk · **P3** = polish / future-proofing.

---

## 1. Administrative hierarchy & Swahili terminology — **PASS**

The `LocationType` enum and D6/D14 chain are correct and use the right Swahili terms in
the right places:

`REGION (Mkoa) → DISTRICT (Wilaya) → COUNCIL (Halmashauri/LGA) → [DIVISION (Tarafa)] →
WARD (Kata) → VILLAGE (Kijiji) / MTAA → HAMLET (Kitongoji)`.

- **COUNCIL/LGA is a first-class level** (D14) — correct, and the single most important
  fix versus the legacy model: services, officers, and report routing genuinely sit at
  the Halmashauri. Verified in `V73`/`V74` (Kilimanjaro and Dar councils seeded beneath
  districts; wards hang off councils, not districts).
- **Council naming reflects real LGA types**: "Moshi Municipal Council", "Ilala City
  Council", "Hai District Council", "Kinondoni Municipal Council" — the Manispaa / Jiji /
  Halmashauri ya Wilaya distinction is honoured, not flattened.
- **WARD = minimum pin granularity** (PRD §9.0) is encoded and documented. Good — this is
  the correct floor: from a Kata you can derive councillor, constituency, and routing.
- **MTAA is modelled as a peer of VILLAGE** (urban vs rural) — correct.
- Honorific/role vocabulary in `V81` parliament roles is accurate and respectful: Spika,
  Naibu Spika, Kiongozi wa Shughuli za Serikali Bungeni, Kiongozi wa Kambi ya Upinzani,
  Waziri / Naibu Waziri, Mwenyekiti wa Kamati, Mnadhimu.

**Note (P3):** WEO (Mtendaji wa Kata) and VEO (Mtendaji wa Kijiji) appear in the routing
legend (Appendix D.1) and `WARD_EXEC` exists as a `RepresentativeType`, but there is **no
seeded executive-officer record** and no Division (Tarafa) tier seeded anywhere. That is
fine for MVP (Tarafa is optional; WEOs are onboarded as responders later), but the team
should not assume a WEO is reachable until the responder directory exists.

---

## 2. Seeded reference data — accuracy & freshness audit

### 2.1 Regions (V71) — **PASS, with a brief-vs-reality correction (already caught)**
- Seeds **26 mainland regions** with ISO-3166-2:TZ-style numeric codes; Zanzibar's 5
  regions deliberately omitted (D17, Phase 2). Songwe (TZ-31, split from Mbeya 2016) is
  present. This is the correct factual mainland set.
- The task brief's "~31 regions" is the **all-Tanzania** total (26 mainland + 5 Zanzibar).
  The seed header already documents this. **No defect** — but the team must remember:
  **31 = mainland + Zanzibar; mainland alone = 26.** Do not "fix" the seed to 31 by
  padding mainland.
- **UNVERIFIED:** I have not line-by-line reconciled all 26 names/codes against the live
  NBS 2022 PHC register or the official TAMISEMI list in this review. Spellings look
  correct on inspection. Treat the code↔name mapping as **needs one authoritative
  pass** before any region goes live (R4/R5 gate).

### 2.2 Districts (V72) — **PASS for structure; UNVERIFIED for completeness**
- All 26 regions carry districts; FK to region resolves by code. Structure is sound.
- **P2 / UNVERIFIED:** the seed header is honest that it models the NBS/citypopulation LGA
  list **at the DISTRICT tier**. District count and "Rural/Town/Municipal" splits drift
  between reorganisations (new councils are gazetted regularly). Examples worth an
  authoritative recheck: Dar's 5 districts (correct as of the 2016 reorg — Kigamboni and
  Ubungo split out), Kondoa Rural vs Kondoa Town, the many "Rural/Town" pairs. **Do not
  treat this list as gospel for any region other than the two deep-detail pilots until a
  TAMISEMI/NBS pass confirms it.**

### 2.3 Councils & wards (V73–V76) — **PASS for the two pilots; coverage gap elsewhere (by design)**
- **Kilimanjaro:** 7 councils (1:1 with districts), Rombo's **24 wards** seeded. The seed's
  claim that Rombo has 24 wards is consistent with the source cited; the round-trip
  find-my-rep / route-a-report works for Rombo.
- **Dar es Salaam:** 5 councils; **Kinondoni (20 wards)** and **Ilala (36 wards)** seeded;
  Temeke/Ubungo/Kigamboni wards deliberately deferred.
- **P2 / UNVERIFIED — ward counts and spellings.** Ward inventories change at delimitation
  and the counts here (Rombo 24, Kinondoni 20, Ilala 36) come from encyclopaedic sources,
  not NEC/INEC or TAMISEMI registers. They are **plausible and internally consistent** but
  must be confirmed against the official register before Kilimanjaro/Dar go-live. Watch
  spellings with apostrophes (Nyang'hwale, Wanging'ombe) and compound ward names
  (Katangara Mrere, Kelamfua Mokala) — these are the ones most often mistyped.

### 2.4 Constituencies & ward→constituency bridge (V78–V79) — **PASS, exemplary honesty**
- 9 Kilimanjaro + 8 Dar constituencies (Majimbo) seeded as a separate overlay entity with
  a display-only district anchor (F2). Good separation of the **electoral** geometry from
  the **administrative** one — this is exactly right for Tanzania.
- **The effective-dated `WardConstituency` bridge is the standout.** Rombo = single
  constituency → all 24 wards map to JIMBO-ROMBO (VERIFIED, complete). For Dar, the author
  mapped **only the name-unambiguous self-anchor wards** (Kawe ward→Kawe jimbo, etc.) and
  **left the rest unmapped on purpose**, with a written warning that a fabricated split
  "would corrupt electoral attribution." **This is precisely the right call.** A wrong
  ward→constituency mapping is one of the worst civic bugs possible — it misattributes a
  citizen's MP and misdirects a binding petition.
- **P1 (gating, not a defect): Dar electoral coverage is incomplete and MUST NOT go live**
  until the official NEC/INEC constituency-ward delimitation fills the gap. Until then,
  Dar find-my-rep at MP level and Dar constituency petitions will silently under-resolve
  for most wards. Owner: geography seed gate (R4/R5). The effective-dating design is
  correct, so re-delimitation (e.g. any 2025-cycle boundary changes) can be layered in
  without rewriting history — but **someone must confirm whether the 2020 delimitation
  used here is still the one in force after the 2025 general election.** Treat the
  `effective_from = 2020-10-28` basis as **UNVERIFIED against the current term.**

### 2.5 Political parties (V80) — **PASS for neutrality; P2 on count/freshness**
- 20 parties seeded as **purely factual reference data** — name, abbreviation, founding
  year only; no field encodes endorsement, strength, or standing. **This is the correct
  neutral posture** (R26). CCM, CHADEMA, CUF, ACT-Wazalendo, NCCR-Mageuzi and the smaller
  registered parties are present with sensible acronyms.
- **P2 / UNVERIFIED — exact count.** Public sources vary between ~19 and ~25 "fully
  registered" parties (the difference is full registration vs provisional, and timing).
  The seed says "full registration." **Action:** reconcile the 20-party list against the
  **Office of the Registrar of Political Parties (ORPP)** official register before any
  party directory is shown publicly — both to drop any that are not fully registered and
  to add any missing one. A wrong party list is low-harm but visible.
- **P3 — political-sensitivity note (NOT a data change):** CHADEMA and ACT-Wazalendo were
  **barred from the 2025 general election** (CHADEMA disqualified 12 Apr 2025; the
  disqualification reportedly extends to by-elections through 2030). This is a live,
  contested political fact. Taarifu must keep these parties in the directory as factual
  registered entities and **must not** annotate them with electoral status, eligibility,
  or commentary — that would breach neutrality (R26) and could draw regulatory scrutiny.
  Keep the data dry; let no field imply a stance. **This whole area needs Legal awareness
  before any election-period feature ships.**

### 2.6 Parliament (V81) — **PASS structurally; P1 date defect**
- Models Union Parliament terms with "one current term per legislature" enforced; 12th
  marked not-current, 13th marked current. Zanzibar HoR correctly deferred (D17).
- **P1 — freshness/accuracy: the 13th Parliament start date is wrong by a day.** The seed
  sets `start_date = 2025-11-12`. Press and parliamentary coverage put the **13th
  Parliament's first sitting and the Speaker's (Mussa Azzan Zungu) election on
  2025-11-11** in Dodoma. Correct the start_date to **2025-11-11** (VERIFY against the
  official Bunge gazette/Hansard for the formal commencement date before fixing — the
  swearing-in week spans a few days, so pin the legally operative date). The 12th's
  dissolution (2025-08-03) is **UNVERIFIED** here and should be confirmed against the same
  source.
- **P2 — coverage:** no actual sitting MPs/councillors are seeded (the `representative`
  table is empty). That is correct — reps are onboarded per-person against their existing
  account (US-0.6/§6.4), not seeded — but it means **find-my-rep returns nothing today**
  even for Rombo. State this plainly to the rollout lead.

### 2.7 Issue categories (V82–V83) — **PASS; strong civic taxonomy**
- 14 top-level categories + 12 distinct-handling sub-categories, **Swahili-first names**
  with English in parentheses. The taxonomy maps cleanly to Tanzanian local civic life
  (Maji na Usafi wa Mazingira, Barabara na Usafiri, Umeme na Nishati, Afya, Elimu,
  Usalama, Ukatili wa Kijinsia, Rushwa na Utawala Bora, Ardhi na Makazi, etc.).
- **Sensitive-category protections are correct and load-bearing:** GBV and
  Corruption/Governance are both `sensitive = TRUE` **and** `force_private = TRUE` →
  routed to `OVERSIGHT`, never PUBLIC. Sub-cases tighten SLAs sensibly (fallen live wire
  4h/48h; disease outbreak 6h/7d; active threat 1h TTFR; crime report → OVERSIGHT). This
  matches Appendix D.4 and the real duty-of-care a Tanzanian handler would expect.
- The routing **tokens** (WARD, MTAA_VILLAGE, COUNCIL, DISTRICT, REGION, SECTOR_UTILITY,
  OVERSIGHT) are a correct abstraction over real responders (TANESCO, DAWASA/RUWASA,
  TARURA/TANROADS, PCCB/TAKUKURU, TPF, CHRAGG, GBV desk) — **not** hardcoded agencies.
  Right design for the D-Q5 staged, region-by-region onboarding reality.

---

## 3. Electoral-vs-residence scoping (D13) & rep gating (F1) — **PASS (model), UNBUILT (enforcement)**

- The **model is correct**: ProfileLocation carries one `isPrimary` (default context) and
  one `isElectoral` (the single location with binding civic weight), voter-ID-authoritative
  with a change cooldown (D13). The `IdType.VOTER` path and `AssociationType` enum exist in
  the identity module. The §9.0/§25.4 edge cases (voter-ID wins on conflict; re-delimitation
  re-resolves via effective-dated WardConstituency; 6-month cooldown; voter-ID bypasses
  cooldown) are specified.
- **Representative gating is correctly schema-enforced:** `ck_representative_mandate_geo`
  guarantees a CONSTITUENCY mandate carries a constituency and no ward; COUNCILLOR_WARD
  carries a ward and no constituency; **SPECIAL_SEATS (Viti Maalum) and NOMINATED carry
  neither** — so "no constituency" is a valid, intentional state for a special-seats woman
  MP or a nominated MP, never a data error. `ux_representative_sitting_constituency`
  enforces one SITTING constituency-MP per Jimbo. **This is exactly how Tanzania's
  Bunge composition works.** Diwani↔ward and Mbunge↔constituency are correctly separated.
- **P1 (enforcement gap):** the **binding-action scoping itself (rate-MP, sign-constituency-
  petition, binding-poll counted only against `isElectoral`) is NOT built** — there is no
  engagement module yet. So the D13 anti-double-influence guarantee is a design, not yet a
  running control. Fine for this stage; must be flagged so no one assumes it is live.

---

## 4. Report routing to the correct authority (responders, D20/D21) — **NOT BUILT (deferred, by design)**

This is the largest civic gap, and it is **intentional** (D-Q5 staged onboarding), but it
must be named honestly:

- **No `responder`, `routing_rule`, or `responder_assignment` tables exist.**
  `report.assigned_responder_id` is an explicit **STUB**; the schema comment says routing
  is DEFERRED and reports stay `NEW`. The owner+collaborator multisectoral model (D21) and
  the §25.2 routing ladder (smallest covering area×category → Council → District →
  Region/sector default → operator queue) are **designed but unimplemented.**
- **Consequence today: a citizen report cannot reach a real office.** It is recorded and
  routed nowhere. The PRD's honest `UNROUTED`/`NO_RESPONDER` operator-queue messaging
  ("Recorded. No responding office is active here yet…") is the correct behaviour, but it
  too is unbuilt.
- **P1 (program, not code):** the close-the-loop promise depends entirely on the
  **government/parastatal onboarding workstream** (R24), which is partnerships, not
  software. Until at least one (area × category) cell is live in a pilot ward, Taarifu is a
  reporting inbox with no outbox. The rollout lead must not let a pilot launch imply
  responsiveness that the responder side cannot yet deliver — that is reputational and
  political risk.

---

## 5. Identity, mobile-money & PDPA realism — **PARTIAL (identity built; rest pending)**

- **Identity tiers (T0→T3)** and the voter-ID-authoritative electoral anchor are built in
  the identity module; PII encryption-at-rest scaffolding (`EncryptedStringConverter`,
  `CryptoPort`) exists. The **operator-assisted verification fallback** for NIDA/voter-ID
  (EI-1/EI-2, D-Q2) is the correct MVP posture — NIDA API access is real but gated and
  slow; do not block the citizen path on it.
- **P2 — NIDA vs voter-roll distinction:** the model correctly treats voter-ID as
  authoritative for *electoral location* and NIDA as person-level identity. Ensure the
  team never conflates the two registers — NIDA coverage and the Daftari la Kudumu la
  Wapiga Kura do not perfectly overlap, and **only the voter roll may set `isElectoral`.**
  This is right in the design; keep it right in code.
- **Mobile money (M-Pesa/Vodacom, Mixx by Yas/Tigo Pesa, Airtel Money, HaloPesa):** not in
  scope of the seeds/schema reviewed (payments are D19 Phase 2). **No defect**, but note for
  later: tokens are metered convenience and **must never buy democratic weight** (D18) —
  the seeded reference data correctly contains no such coupling.
- **PDPA (2022 + 2023 regs, PDPC):** the PII-encryption and audit scaffolding is consistent
  with PDPA obligations. **P2 / needs Legal:** lawful basis, consent capture, data-subject
  access/erasure flows, and in-country hosting for NIN/voter-ID/MSISDN are design intents
  (§18, §25.1) not yet verifiable as built. Flag for the security/privacy lead and Legal
  before any real PII is collected in a pilot.

---

## 6. USSD / feature-phone inclusion — **DESIGNED, NOT BUILT**

- The PRD treats USSD/SMS as the reach strategy (not a nice-to-have), with reply-convention
  close-the-loop ("Jibu 1=Thibitisha, 2=Kataa") and GSM-7-aware phrasing. The taxonomy's
  Swahili-first short names suit a USSD menu and low-literacy users.
- **P2:** the `communications` schema (V27–V29: announcement/subscription/notification)
  and a `SmsGateway` port exist, but **no USSD session handling, no aggregator/sender-ID
  integration, and no GSM-7 vs UCS-2 handling** are built. For Tanzanian reach (feature
  phones common rurally; data costly relative to income; TCRA-regulated SIM/sender-ID
  procurement), this is a **must-have before rural go-live**, not a later polish. Note the
  Swahili-character trap: words with accented/special characters force UCS-2 (70-char
  segments, higher cost) — keep citizen-facing SMS GSM-7-safe.

---

## 7. Election-period neutrality — **PASS (data), needs ongoing guardrails**

- Seeded reference data is neutral: parties carry no endorsement field; reps will be shown
  factually (status SITTING/FORMER), never promoted. Taarifu is correctly positioned as
  **not an electoral/voting system** (§3.2 non-goal; R26).
- **P2 / needs Legal:** the live 2025 political context (opposition parties barred from the
  general election; INEC — note the commission was renamed from NEC to **INEC** —
  administering a largely uncontested poll) means a Tanzanian civic platform is operating
  in a **scrutiny-heavy environment**. Before any election-window feature, rep profile, or
  party directory goes public, run election-period content rules and a Legal review (R26).
  **Do not** add any field, badge, or copy that could read as commentary on a party's or
  candidate's standing or eligibility.

---

## 8. Prioritised gaps & recommendations

| # | Sev | Area | Finding | Recommendation | Owner |
|---|-----|------|---------|----------------|-------|
| 1 | **P1** | Parliament freshness | 13th Parliament `start_date` seeded as **2025-11-12**; the first sitting / Speaker election was **2025-11-11**. | New forward migration to correct to 2025-11-11 (verify operative date vs official Bunge gazette/Hansard first); confirm 12th dissolution date. | DB eng + domain |
| 2 | **P1** | Electoral mapping (Dar) | Most Dar wards have **no** ward→constituency mapping (deliberately blank). MP-level find-my-rep / Dar petitions under-resolve. | Block Dar go-live until official NEC/INEC delimitation fills `ward_constituency`; confirm 2020 delimitation still in force post-2025. | Geography seed gate |
| 3 | **P1** | Routing / responders | No responder/routing tables; `assigned_responder_id` is a STUB. Reports reach no office. | Build the responder directory + routing ladder + `UNROUTED` operator queue (§25.2) and onboard ≥1 live (area×category) cell before any pilot claims responsiveness. | Backend + program |
| 4 | **P1** | Election neutrality / Legal | Live 2025 context (opposition barred; NEC→INEC) makes party/rep display politically sensitive. | Keep party/rep data strictly factual; Legal review + election-period content rules before any public rep/party feature. | Legal / Product |
| 5 | **P2** | Reference-data freshness | District/ward/party counts & spellings come from encyclopaedic sources, not TAMISEMI/NBS/ORPP/NEC registers. | One authoritative reconciliation pass per pilot region before go-live; treat counts as **UNVERIFIED** until then. | Domain + DB eng |
| 6 | **P2** | USSD/SMS reach | No USSD session, aggregator/sender-ID, or GSM-7 handling built. | Build USSD/SMS path (GSM-7-safe Swahili copy, TCRA sender-ID procurement) before rural go-live. | Integrations |
| 7 | **P2** | PDPA | Consent/lawful-basis/erasure/in-country-hosting are design intents, not verified as built. | Security/privacy lead + Legal sign-off before any real PII collected in a pilot. | Security + Legal |
| 8 | **P3** | Executive officers | No WEO/VEO/Tarafa records; WARD/MTAA_VILLAGE tokens have no live target. | Onboard WEOs/VEOs as responders during region enrichment; Tarafa optional. | Program |
| 9 | **P3** | Region count clarity | "31 regions" (brief) = 26 mainland + 5 Zanzibar; mainland seed correctly = 26. | Keep mainland at 26; do not pad. Zanzibar is Phase 2 (D17). | (resolved — note only) |

---

## 9. Open questions for the team

1. **Which pilot region first?** If Kilimanjaro/Rombo, the geography round-trip is the most
   complete and the cleanest place to prove the loop — but reps and responders are still
   empty, so even Rombo cannot close a report today.
2. **Is the 2020 delimitation still in force after the 2025 general election?** If 2025
   changed any ward/constituency boundaries, the `effective_from = 2020-10-28` rows need a
   closing date and a new effective-dated set. **Needs authoritative NEC/INEC confirmation.**
3. **Who owns the authoritative reference-data reconciliation** (TAMISEMI/NBS for
   admin geography, NEC/INEC for wards↔constituencies, ORPP for parties, Bunge for
   parliament dates)? This should be a named workstream with a sign-off gate per region.
4. **Has Legal been engaged on election-period operations and PDPA** ahead of any public
   pilot? Given the 2025 political climate, this is not optional.

---

## Sources (live-fact checks)

- [Zungu Elected Speaker as Tanzania's 13th Parliament Kicks Off in Dodoma — The Chanzo](https://thechanzo.com/2025/11/12/zungu-elected-speaker-as-tanzanias-13th-parliament-kicks-off-in-dodoma/)
- [Stage set for 13th Parliament — Daily News](https://dailynews.co.tz/bunge-resumes-as-speaker-pm-confirmation-among-key-tasks/)
- [National Assembly (Tanzania) — Wikipedia](https://en.wikipedia.org/wiki/National_Assembly_(Tanzania))
- [List of political parties in Tanzania — Wikipedia](https://en.wikipedia.org/wiki/List_of_political_parties_in_Tanzania)
- [2025 Tanzanian general election — Wikipedia](https://en.wikipedia.org/wiki/2025_Tanzanian_general_election)
- [Tanzania's opposition party Chadema disqualified from 2025 election — The Citizen](https://www.thecitizen.co.tz/tanzania/news/national/tanzania-s-opposition-party-chadema-disqualified-from-2025-election-5000048)
- [Tanzania's main opposition Chadema party barred from upcoming elections — Al Jazeera](https://www.aljazeera.com/news/2025/4/12/tanzanias-main-opposition-chadema-party-barred-from-upcoming-elections)

> **Verification posture:** parliament dates and the 2025 election/party-bar facts are
> cross-checked against the sources above. **Region/district/ward/constituency/party
> *counts and spellings* are UNVERIFIED against official TAMISEMI/NBS/NEC-INEC/ORPP
> registers** and need an authoritative pass per pilot region before go-live. Reference
> data changes between elections and reorganisations — treat freshness as a standing risk.
