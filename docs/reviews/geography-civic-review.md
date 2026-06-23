# Geography & Civic Correctness Review — `geography` module

**Reviewer:** Mzee Salehe Kombo (Tanzania Civic & Governance Domain Expert)
**Date:** 2026-06-23
**Scope:** `backend/.../geography/**` entities + `V2__geography.sql`, reviewed against PRD §9.0/§9.1 (D6, D14, D17, EI-7, EI-14) and Tanzanian administrative/electoral reality.
**Verdict:** **PASS with minor fixes.** The model is civically correct and faithful to the locked decisions. No locked decision is violated. The issues below are seed-data / validation / Phase-2-readiness gaps, not structural redesigns.

---

## 1. What is correct (confirmed)

| Area | PRD anchor | Finding |
|---|---|---|
| Admin hierarchy levels | §9.0 D6/D14 | `LocationType` = REGION, DISTRICT, COUNCIL, DIVISION, WARD, VILLAGE, MTAA, HAMLET — exactly the D6 chain, **Council/LGA and Division present**. Correct. |
| Swahili civic vocabulary | CLAUDE.md §8 | Javadoc maps every level to its real term (Mkoa/Wilaya/Halmashauri/Tarafa/Kata/Kijiji/Mtaa/Kitongoji). MTAA is correctly modelled as the urban **peer** of VILLAGE, HAMLET (Kitongoji) as the finest unit. Correct. |
| Two overlapping geographies | §9.0 | Admin chain (closure table) and electoral mapping (`Constituency` + `WardConstituency`) are separate; constituency is its own entity, not an admin level. Correct — this resolves the legacy Region/District/Constituency ambiguity. |
| Constituency homed in District | §9.1 | `Constituency.district` → `Location` (DISTRICT). Member wards only via the bridge, never a direct ward FK. Correct. |
| Effective-dated bridge | §9.0, EI-14 | `WardConstituency` is effective-dated; DB owns the two invariants JPA cannot (partial unique `ux_ward_constituency_current`, and `EXCLUDE … gist` no-overlap). Date-scoped resolution (`findEffectiveMapping`) means history never re-points on re-delimitation. **This is exactly right** and is the most important correctness property in the schema. |
| Ward = minimum pin | §9.0 | Resolution derives councillor + constituency + routing from the ward; village/hamlet optional; GPS→ward via PostGIS with graceful degrade (EI-7). Correct. |
| Civic retirement vs delete | §9.1 | `LocationStatus` ACTIVE/INACTIVE separate from soft-delete. Correct — superseded wards stay resolvable for old reports. |

---

## 2. Fixes required (concrete)

### F1 — [MEDIUM] No parent-type validation: hierarchy integrity is unenforced
`Location.parent` is an untyped self-FK. Nothing (entity, DB, or service) prevents a WARD whose parent is a REGION, or a COUNCIL under a HAMLET. PRD §9.0/D6 defines a **specific** legal chain, and `R4`/`R5` (bad/stale seed) are live risks. The closure table will faithfully store whatever wrong ancestry it is fed.

**Allowed parent matrix (enforce on write + at seed-import validation):**

| Child | Legal parent type(s) |
|---|---|
| REGION | (none — root; `parent_id` NULL) |
| DISTRICT | REGION |
| COUNCIL | DISTRICT |
| DIVISION | COUNCIL |
| WARD | COUNCIL **or** DIVISION (Tarafa optional) |
| VILLAGE | WARD |
| MTAA | WARD |
| HAMLET | VILLAGE **or** MTAA (Kitongoji sits under a Kijiji rurally, under a Mtaa in town) |

**Fix:** add a domain invariant (a `LocationType.canParent(childType)` rule on the enum, checked in the create/import service) plus an EI-14 bulk-import validation pass that rejects illegal parent types before insert. Do **not** try to encode this as a DB CHECK across the self-join — keep it in the domain.

### F2 — [MEDIUM] `Constituency.district_id` should resolve to a real DISTRICT, and the "DISTRICT" anchor is the weak spot for cities
PRD §9.1 says a constituency "belongs to District". Two real-world cautions for the seed engineer:
- The FK is untyped — seed validation must assert `district_id` points at a `type = DISTRICT` row (not a COUNCIL or REGION).
- **Reality check (UNVERIFIED for any specific area):** in Tanzanian practice a *jimbo* (constituency) frequently aligns with a **council/halmashauri** rather than the bare *wilaya*, and a single district can contain multiple councils (e.g. a Municipal Council + a District Council). The model is still sound because membership is driven by the **ward→constituency bridge**, not by the district anchor — but flag to the seed team that `district_id` is a *homing/display* anchor only and must never be used to derive a constituency's wards. Add a code comment to that effect on `Constituency.district`.

### F3 — [LOW] Code format in seed/tests is a placeholder, not the official NBS/PO-RALG code
`GeographyTestData` uses `TZ-19`, `TZ-1907`, `TZ-1907-WD-MENGWE`, `TZ-JIMBO-ROMBO`. `Location.code` is the **idempotent match key for EI-14 re-import**, so its format is load-bearing. Tanzania's authoritative reference is the **NBS 2022 PHC** geo-database, which codes the five levels (Region → District → Ward/Shehia → Village/Mtaa → EA) with **numeric hierarchical codes**. Decision needed before real seed:
- Adopt the **official NBS numeric code** as `Location.code` (recommended — it is the real idempotent key and lets us reconcile against census/boundary shapefiles), and keep any `TZ-…` string only as a human label if wanted.
- `VARCHAR(32)` is wide enough; no schema change. This is a **seed-data sourcing decision**, not a code defect — but the placeholder must not ship as the seeded key. Owner: database-engineer + me (EI-14).

### F4 — [LOW] Constituency carries no `legislature` discriminator — needed before Zanzibar Phase 2 seed
PRD §9.0/§9.1/D17: Zanzibar has its **own** admin structure and its **own legislature (Baraza la Wawakilishi / House of Representatives)** alongside the Union Bunge. The PRD parks the `legislature {UNION_PARLIAMENT, ZANZIBAR_HOR}` discriminator on `Representative` (§9.1, §9.2), which is acceptable for MVP. But note for the design owner: a Zanzibar **electoral constituency** (jimbo la Baraza la Wawakilishi) is a *different kind of seat* from a Union constituency. When Phase 2 lands, decide whether `Constituency` needs a `legislature`/`tier` field, or whether the Union-vs-Zanzibar distinction lives entirely on `Representative.legislature`. **Not a blocker now** — the generic model "already supports it" as §9.0 requires (a constituency homed in a Zanzibar district works today). Just record the open question in the institutions ADR so it isn't lost.

### F5 — [LOW] No representation of the "no constituency" case in the geography module — confirm it lives in `institutions`
Special-seats (**Viti Maalum**), nominated (**Wabunge wa Kuteuliwa**) and ex-officio MPs carry `mandate ≠ CONSTITUENCY` and a **null** constituency FK. PRD §9.1 correctly places `mandate` and the nullable constituency FK on `Representative` (institutions module, still a stub). **Confirmed: geography is the right place for none of this** — flagging only so the institutions owner does not push mandate logic down into geography. The geography model is complete as-is for this concern.

---

## 3. Watch items (no change now)

- **W1 — Diaspora pins (§9.0):** electoral/home stays a real TZ ward while residence may be non-TZ. Geography has no country dimension; today every `Location` is implicitly Tanzania. Fine for mainland MVP; revisit if non-TZ residence ever needs a pinned place (likely handled in `ProfileLocation`, not geography).
- **W2 — Shehia (Zanzibar):** Zanzibar's ward-equivalent is the **Shehia**, led by a **Sheha** — there is no exact mainland LocationType peer. When Phase 2 seed is designed, decide whether Shehia maps onto WARD or needs its own type. The generic enum tolerates either; do not add it pre-emptively (KISS).
- **W3 — Seed freshness (R4/R5):** wards and constituencies are re-delimited between elections; ~31 regions, ~184 districts, ~4,300+ wards as of the 2022 census. Treat the seed as perishable; the effective-dated bridge already protects history.

---

## 4. Bottom line
Civically sound and aligned with PRD §9.0 and every relevant locked decision (D6, D14, D17, EI-7, EI-14). Ship the structure. Before real reference data loads, close **F1** (parent-type validation) and **F3** (official NBS codes), and record **F4** as an institutions/Phase-2 open question. F2/F5 are guard-rail comments. Nothing here needs Legal.

---
### Sources (Tanzanian reference data)
- [NBS — Census 2022 (administrative geography, 5 levels)](https://www.nbs.go.tz/statistics/topic/census-2022)
- [NBS — Region Shapefile Metadata 2022 Census](https://microdata.nbs.go.tz/index.php/catalog/49)
- [Regions of Tanzania (Wikipedia)](https://en.wikipedia.org/wiki/Regions_of_Tanzania)
- [Districts of Tanzania (Wikipedia)](https://en.wikipedia.org/wiki/Districts_of_Tanzania)
