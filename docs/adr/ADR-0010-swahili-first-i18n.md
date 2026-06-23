# ADR-0010: Swahili-first internationalisation (SW default + EN)

**Status:** Accepted · 2026-06-23 · Solution Architect
**Grounding:** PRD §19 D-Q10 (SW default + EN), §15 (i18n NFR), §17 (localised messages); CLAUDE.md §8 (i18n-ready).

## Context
Taarifu is a Tanzanian civic platform whose primary users speak **Swahili**, often on feature phones, frequently low-literacy (PRD §14, §15). Localisation cannot be an afterthought bolted on later: **every** user-facing string — including API error/`message` text, notification/SMS/USSD copy, and validation messages — must be externalised and Swahili-first from day one (PRD §19 D-Q10). Domain terms must use the **correct Swahili civic vocabulary** (Mkoa, Wilaya, Halmashauri, Kata, Jimbo, Mbunge, Diwani — CLAUDE.md §8).

## Decision
- **Swahili is the default locale; English is the secondary** locale, from the first increment.
- **All** user-facing strings are externalised to resource bundles (`i18n/messages_sw.properties`, `messages_en.properties`); code references **i18n keys** (`<module>.<concept>.<detail>`), never literals.
- The **`ApiResponse.message`** and validation/error text are **localised server-side** via `common.i18n.MessageResolver`, driven by the request's `Accept-Language` / user preference; stable machine `code`s stay language-independent so clients branch on `code`, not text (ADR-0008).
- **Domain identifiers keep the real Swahili civic terms** where they are the actual name (geography, representative types); UI labels are translated.
- Notifications carry a per-user **language** preference (PRD §13, §9.1 `NotificationPreference`); SMS uses **UCS-2** for full Swahili (PRD §21 EI-3).
- Right-to-grow to more languages: the bundle/key structure adds locales without code change.

## Consequences
- (+) The product is usable and respectful in Swahili from launch; adding a language is a translation task, not a refactor.
- (+) Machine `code` + localised `message` cleanly separates client logic from human copy.
- (−) Every feature must ship SW+EN strings to be "done" (CLAUDE.md §9) — accepted; enforced in review.
- (−) Translation upkeep is ongoing; mitigated by key conventions and a single bundle location.
- **Revisit trigger:** add further locales (e.g. for cross-border or accessibility needs) by adding bundles; the architecture already supports it.
