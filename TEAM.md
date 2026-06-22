# Taarifu — Technical Team (Subagents)

A full software-delivery team for the **Taarifu** Tanzania civic-engagement platform, defined as **Claude Code subagents** in [`.claude/agents/`](.claude/agents/). Each is a senior persona grounded in [PRD.md](PRD.md) and [SYNOPSIS.md](SYNOPSIS.md), and honours the locked decisions. Tanzanian rules/norms are woven through the roles that need them, with a dedicated domain expert as the authority.

## Roster

| Agent (`subagent_type`) | Persona | Role | Focus | 🇹🇿 | Tools |
|---|---|---|---|---|---|
| `project-manager` | Asha Mwakyusa | Senior Delivery & Project Manager | Scope, milestones (M-Foundations→MVP→P2/P3), rollout & D3/D5 onboarding programs, risks, go/no-go | deep | advisory |
| `business-analyst` | Neema Kessy | Senior Business / Requirements Analyst | Requirements, user stories (US-x.y), use cases (UC-x), PRD validation & traceability | deep | advisory |
| `solution-architect` | David Okello | Principal Solution Architect | Architecture, NFRs, module boundaries, pluggable-adapter ports, ADRs, scalability | some | all |
| `backend-engineer` | Baraka Mushi | Senior Backend Engineer | Spring Boot/Java 21, JPA, Flyway, REST+OpenAPI, RBAC, tokens, routing | some | all |
| `frontend-engineer` | Grace Mtui | Senior Frontend Engineer | Angular 18 admin + web/PWA, i18n (SW/EN), offline, low-end perf, a11y | some | all |
| `mobile-engineer` | Juma Hassan | Senior Flutter Mobile Engineer | Flutter/BLoC, offline drafts+sync, FCM, low-data, feature-phone parity | yes | all |
| `database-engineer` | Fatma Ally | Senior Database / Data Engineer | PostgreSQL/PostGIS, geography + electoral mapping, token ledger, migrations, perf | yes | all |
| `integrations-engineer` | Emmanuel Shirima | Integrations Engineer | SMS/USSD, mobile money, NIDA/voter, FCM, email; adapters, retries, webhooks | deep | all |
| `qa-engineer` | Rehema Massawe | Senior QA / Test Engineer | Test strategy & automation, AC verification, multi-channel + SW/EN, launch gate | some | all |
| `devops-sre` | Peter Nyerere | Senior DevOps / SRE | CI/CD, Docker/K8s/Helm, IaC, observability, in-country hosting, DR | yes | all |
| `security-privacy-engineer` | Salim Juma | Security & Privacy Engineer | OWASP/threat modeling, PII encryption, **PDPA 2022/2023**, security gate | yes | all |
| `ux-ui-designer` | Lulu Mwinyi | Senior Product / UX Designer | Low-literacy, **Swahili-first**, WCAG, USSD/SMS UX, flows/microcopy | deep | advisory |
| `tanzania-domain-expert` | Mzee Salehe Kombo | Tanzanian Civic & Governance Domain Expert | Admin+electoral structure, NIDA/voter, mobile money, PDPA, neutrality | **core** | advisory |
| `trust-safety-moderator` | Zainab Ramadhani | Trust & Safety / Moderation Lead | Swahili moderation, GBV/sensitive handling, anti-abuse, appeals, policy/SLAs | yes | advisory |
| `enduser-citizen-smartphone` | Amina Juma | End user — citizen (smartphone) | Candid UAT feedback: clarity (SW), friction, trust, data cost | yes | read-only |
| `enduser-citizen-featurephone` | Joseph Mussa | End user — citizen (feature phone) | USSD/SMS inclusion, short Swahili, low-literacy, cost | yes | read-only |
| `enduser-representative` | Hon. Neema Kileo | End user — MP/Councillor | Rep-facing features, accountability fairness, single-account experience | yes | read-only |
| `enduser-area-official` | Mr. Mushi | End user — government area official | Case-management practicality in a resource-constrained office | yes | read-only |

*Tools: "all" = builders/reviewers (full toolset incl. Bash/Write/Edit); "advisory" = Read/Grep/Glob/Write/Edit/WebSearch/WebFetch/TodoWrite; "read-only" = Read/Grep/Glob (personas give feedback, don't edit).*

## How to invoke

These run via the **Agent** tool with `subagent_type`. Examples (what to ask Claude):
- *"Use the **solution-architect** to design the token/wallet module boundaries and the payment-adapter port."*
- *"Have the **tanzania-domain-expert** verify the issue-category routing in Appendix D matches how a Halmashauri actually handles water complaints."*
- *"Ask **enduser-citizen-featurephone** to review the USSD reporting flow (§28 J2) and flag anything that excludes feature-phone users."*
- *"Get the **security-privacy-engineer** to threat-model the ID-verification flow and check PDPA compliance."*
- *"Run a design review of the reporting epic with **business-analyst**, **backend-engineer**, **qa-engineer**, and **enduser-area-official** in parallel."*

You can also fan several out at once (e.g. a multi-perspective review) or chain them across the lifecycle.

## Suggested lifecycle line-up

| Phase | Lead agents | Supporting / review |
|---|---|---|
| **Analyze** | business-analyst, tanzania-domain-expert | project-manager, end-users |
| **Design** | solution-architect, ux-ui-designer, database-engineer | security-privacy-engineer, integrations-engineer, domain-expert |
| **Build** | backend-, frontend-, mobile-, integrations-, database-engineer | architect (review), security (review) |
| **Test** | qa-engineer, trust-safety-moderator | security-privacy-engineer, all four end-users (UAT) |
| **Deploy** | devops-sre, security-privacy-engineer | project-manager (go/no-go), domain-expert (region readiness) |

> Personas are advisory lenses, not authorities over the PRD — the [PRD.md](PRD.md) §19/§25.10 decision register remains the single source of truth.
