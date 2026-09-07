# Break-Window Response — Architecture & Design Document

**Status:** Draft v1.0 — governing document for implementation
**Audience:** Human contributors *and* agentic coding tools (Claude Code, Codex, Google Antigravity, etc.)
**Author:** Dennis Gathu Mwangi
**Stack lineage:** `pitch-pipe` → `temporal-squad` → `press-logic` → `formation-stream` → **`break-window-response`**
**Architectural model:** [Parens to Production](https://mapidentity.github.io/parens-to-production/) — a server-rendered, framework-free Clojure/Datomic SaaS, built, operated, and defended on one box you own.

---

## 0. How to use this document

This is the single source of truth for the project. If you are a human developer or an agentic coding tool starting work on this repository, read this document in full before writing code. Every section states not just *what* to build but *why*, so that implementation decisions downstream can be checked against intent rather than re-litigated from scratch.

Rules for anyone (human or agent) implementing against this document:

1. **Do not introduce a framework where a library will do.** No SPA framework, no ORM, no dependency-injection container. Ring + http-kit + Reitit + Hiccup, as in the reference architecture. If you believe an exception is warranted, write an ADR (§14) before writing code.
2. **Every ingestion boundary is `clojure.spec`-validated before the data touches business logic.** Malformed data is rejected at the door, not caught downstream. This is not optional and not something to "get to later."
3. **Every derived claim in the system must be traceable to the rule or data that produced it.** No black-box scores. If you cannot explain in one sentence why the system produced a given recommendation, the design is wrong, not the explanation.
4. **Security is non-negotiable.** Not a checklist run once before ship — a property maintained continuously (§9). A pull request that weakens a security control does not merge on the promise of a follow-up fix.
5. **When in doubt, do less, and do it as data.** Prefer a `core.logic` relation or an EDN rule to an imperative branch. Prefer a Datomic query to an in-memory loop. Prefer composing existing verbs (`map`, `filter`, `transduce`, `d/q`) to inventing new control flow. This is the Rich Hickey standard this project holds itself to: simplicity is a prerequisite for correctness, and "simple" means *un-braided*, not "easy" or "familiar."

---

## 1. Problem statement and scope

### 1.1 What this project is

FIFA made in-match hydration breaks mandatory at the 2026 World Cup. An existing, rigorous public study ([valternunez/wc2026-momentum](https://github.com/valternunez/wc2026-momentum)) has already asked and carefully answered the aggregate causal question — *does a hydration break move momentum more than any other stoppage, in general, across the tournament?* — using FotMob's proprietary per-minute momentum index, duration-matched stoppage comparisons, and multiple historical placebo controls. That study's honest conclusion: a small, not-yet-statistically-distinguishable-from-zero gap once regression to the mean is accounted for.

**This project does not re-ask that question.** It asks a different, complementary one, aimed at a different audience:

> *Given that a break is coming at a known, fixed point in the match (22' and 67'), what does a specific team actually do with it — and what should a coach's staff watch for, expressed as auditable, inspectable rules rather than an opaque score?*

This is decision support for a technical staff, not a public causal-inference report. It is deliberately built from named, independently verifiable signals — shot timing, goal timing, substitution timing, and (where tracking-adjacent data is available) pressing/compactness proxies in the style of `formation-stream` — rather than a third-party black-box momentum index. This mirrors the design thesis already running through `press-logic`: *"prioritises coach interpretability over model accuracy — every trigger can be traced back to the rule that fired."*

### 1.2 What this project is not

- **Not** a tournament-wide causal study. It does not need 104 matches to be useful; it is designed to produce a defensible per-team, per-match "break-window report" from a handful of matches with clean data.
- **Not** a replacement for `press-logic`'s pressing-trigger engine — it is a sibling system that shares its rule-engine philosophy and, where useful, reads from it.
- **Not** a live betting or prediction product. No odds, no stake sizing (that is `house-hedge`'s domain).
- **Not** dependent on scraping FotMob/SofaScore's proprietary momentum widgets. Their terms of service treat that data as derived-only and non-redistributable, and — more importantly for this project's thesis — that index is exactly the kind of black box this system exists to be an alternative to.

### 1.3 Success criteria

- A coach or analyst can open a match report, see a specific break-window recommendation ("Team X used the 67' break to make its first substitution in 6 of its last 8 matches; expect a like-for-like swap unless trailing by 2+"), and click through to the *exact* underlying events that produced it.
- Every recommendation is generated by a named, versioned rule in `core.logic`, stored as data, not code that has to be read to be audited.
- The system runs, in production, on a single box the author controls, per the reference architecture — no managed database, no PaaS lock-in, no third-party API dependency at request-serving time.

---

## 2. Guiding principles

These are load-bearing. Every design decision in later sections should be checkable against this list.

1. **Data > functions > macros** (Hickey's ordering). Model the domain as data first (EDN, Datomic schema). Write functions that transform that data. Reach for a macro only when a function genuinely cannot express the shape you need — and treat that as a rare event requiring justification.
2. **Simplicity is about *interleaving*, not familiarity.** A "simple" design is one where each part addresses one concern and can be reasoned about, tested, and replaced independently. Complecting the ingestion boundary with the rule engine, or the rule engine with the HTTP layer, is a defect even if it's less code.
3. **Values, not places.** Prefer immutable data flowing through pure transformations to mutable state threaded through objects. Where state is unavoidable (the web server, the job queue), isolate it behind the smallest possible surface (`atom`, `ref`, or a Datomic transaction) and keep everything else pure.
4. **The database is the audit log.** As in `temporal-squad`, use time-aware storage so that "what did we know, and when" is a query, not a regret. Every stoppage-response recommendation is provenance-stamped with the data and rule version that produced it.
5. **Boundary validation is not defensive programming, it's the contract.** `clojure.spec` at every I/O edge (HTTP request bodies, ingested match data, form submissions) — not because untrusted input is scary, but because a validated boundary is what lets everything behind it be written with the assumption of well-formed data, exactly as `pitch-pipe` already does.
6. **Explainability is a first-class deliverable**, not a debug feature. Every screen that shows a recommendation must render, next to it, the rule that fired and the data that satisfied it.
7. **One box, owned.** No managed Postgres, no managed queue, no serverless functions. Systemd units, a reverse proxy with automatic TLS, a firewall you configured, backups you drilled. This is the same posture as the reference architecture, deliberately: *operable* is a design constraint, not an ops afterthought.

---

## 3. Technology stack

| Layer | Choice | Rationale |
|---|---|---|
| Language | Clojure (JVM) | Consistent with the whole arc; REPL-driven development; data-first idioms |
| Web server | http-kit | Same as reference architecture; async-capable, minimal |
| Routing | Reitit | Data-driven routes (route table is EDN, inspectable, testable without a running server) |
| Middleware | Ring | Standard, composable, no framework lock-in |
| Views | Hiccup, server-rendered | No client framework; progressive enhancement only where it earns its keep (see §7) |
| Database | **XTDB** (bi-temporal) — see ADR-001 (§14.1) | Reuses the bi-temporal modelling already proven in `temporal-squad`; "what did we know when this recommendation fired" is a native query, not bolted on |
| Ingestion validation | `clojure.spec.alpha` | Boundary contracts, generative testing, matches `pitch-pipe` |
| Ingestion pipeline | Transducers | Single-pass parse → validate → enrich, zero intermediate collections, matches `pitch-pipe` |
| Rule engine | `core.logic` | Explainable, queryable stoppage-response rules as data, matches `press-logic` |
| Async/streaming | `core.async` | Only where genuinely concurrent (live match polling during a tournament); explicit backpressure per `formation-stream`'s precedent — `offer!` where drop is acceptable, `>!` where it isn't |
| Auth | Passwordless, HMAC-signed single-use magic links | Matches reference architecture; no password store to breach |
| Dev environment | Devcontainer (Docker, Caddy, Mailpit, TLS) | Checked-in, reproducible, matches reference architecture exactly (§4) |
| Testing | `clojure.test` + `test.check` (generative) + Playwright (E2E) | Matches reference architecture |
| Deployment | systemd units, Caddy (automatic TLS), single box | Matches reference architecture |
| CI/CD | GitHub Actions, softened deploy window, CVE gate | Matches reference architecture |

**Do not substitute** React/re-frame, a REST-then-JSON SPA, Postgres-as-primary-store, or a managed job queue (SQS/etc.) without an ADR justifying the deviation. The entire point of this stack is that it is one coherent, auditable system, not a grab-bag of best-of-breed services.

---

## 4. Development environment

Mirror the reference architecture's devcontainer chapter exactly:

```bash
git clone <this-repo>
code break-window-response        # open in VS Code, "Reopen in Container"
# devcontainer builds once (JDK, Node if needed for asset pipeline, Caddy, Mailpit, TLS certs)
clj -M:dev:repl                   # nREPL, editor connects
(start!)                          # server + file watcher, :3000
```

- The in-container browser hits `https://breakwindow.lan` — the real TLS front door the app's URLs are built around.
- From the host machine: the editor's forwarded `http://localhost:3000`.
- Everything required to reproduce this is **checked into the repo**. A fresh clone must run identically on any machine. No "works on my machine" steps that live only in a README.
- Strict compilation is on from day one: reflection warnings and boxed math are build failures, not lint suggestions, per the reference architecture's approach.

---

## 5. Data model

### 5.1 Core entities (XTDB documents / Datomic-style attributes — see ADR-001)

```clojure
;; A tournament
{:tournament/id         "wc2026"
 :tournament/name       "FIFA World Cup 2026"
 :tournament/start-date #inst "2026-06-11"
 :tournament/end-date   #inst "2026-07-19"}

;; A match
{:match/id           "M42"
 :match/tournament   "wc2026"
 :match/home-team     "France"
 :match/away-team     "Iraq"
 :match/kickoff       #inst "2026-06-22T15:00:00Z"
 :match/venue         "Philadelphia Stadium"
 :match/data-source   :source/fbref            ; provenance, always
 :match/data-quality  :quality/verified}        ; see §5.3

;; A scheduled stoppage (the break itself — known in advance, not detected)
{:stoppage/id           "M42-break-1"
 :stoppage/match        "M42"
 :stoppage/type         :stoppage.type/hydration
 :stoppage/half          1
 :stoppage/clock-minute 22
 :stoppage/duration-s   180}

;; A raw match event (goal, card, substitution, shot — whatever the source provides)
{:event/id       "M42-evt-0113"
 :event/match    "M42"
 :event/type     :event.type/substitution      ; :goal | :card | :substitution | :shot
 :event/minute   23
 :event/team     "France"
 :event/detail   {:player-off "Griezmann" :player-on "Thuram"}
 :event/source   :source/fbref}

;; A rule (data, not code — evaluated by core.logic)
{:rule/id          :rule/break-window-substitution-pattern
 :rule/version      "1.0.0"
 :rule/description "Flags a team's tendency to make its first substitution inside a break window"
 :rule/window-s     300   ; seconds either side of the stoppage clock-minute
 :rule/predicate    '(break-adjacent-substitution ?team ?stoppage ?event)}

;; A generated recommendation (always provenance-stamped)
{:recommendation/id           "M42-rec-004"
 :recommendation/match        "M42"
 :recommendation/stoppage     "M42-break-1"
 :recommendation/rule         :rule/break-window-substitution-pattern
 :recommendation/rule-version "1.0.0"
 :recommendation/team         "France"
 :recommendation/text         "France made their first substitution inside a break window in 6 of their last 8 matches."
 :recommendation/evidence     ["M42-evt-0113" "..."]   ; the exact events that satisfied the rule
 :recommendation/generated-at #inst "2026-07-22T10:04:11Z"
 :recommendation/tx           1234567}                  ; the transaction/valid-time this was derived under
```

### 5.2 Why bi-temporal storage (ties directly to §14.1)

A recommendation shown to a coaching staff before a match must be reproducible after the fact: *what did the system know, and what rule version was active, at the moment it produced this advice?* This is precisely the problem `temporal-squad` already solved for player state. Reusing that pattern here means "what did we recommend, and why, as of kickoff" is a query against valid-time/transaction-time, not a log grep.

### 5.3 Data quality is a first-class attribute, not an afterthought

Every `:match` and `:event` carries `:data-source` and `:data-quality`. Recommendations are only generated from `:quality/verified` matches. This is the direct consequence of §1.1: this system's credibility rests on every claim being traceable, and a claim traceable to unverified scraped data is not traceable to anything trustworthy. Build the quality gate before the rule engine, not after.

---

## 6. Ingestion pipeline (`bwr.ingest`)

Modeled directly on `pitch-pipe`'s transducer-based, spec-validated-at-the-boundary design.

```clojure
(ns bwr.ingest.pipeline
  (:require [clojure.spec.alpha :as s]
            [bwr.ingest.spec :as spec]))

;; Single-pass: parse -> spec-validate -> enrich -> emit
;; No intermediate collections between stages.
(defn ingestion-xf [source]
  (comp
    (map (partial parse-record source))
    (filter (partial s/valid? ::spec/match-event))   ; malformed rejected here, not downstream
    (map enrich-with-stoppage-window)
    (map tag-provenance)))

(defn ingest! [source raw-records]
  (transduce (ingestion-xf source) conj-to-store [] raw-records))
```

- **Sources:** Wikipedia/FBref match reports for goals, cards, substitutions, and shot minutes (ToS-clean, structured, no login wall). Do **not** scrape FotMob/SofaScore's momentum widgets — see §1.3.
- **Malformed records are rejected at the boundary**, logged with the reason, and never reach the rule engine. This is a hard requirement, not a nice-to-have — it's the same contract `pitch-pipe` already makes to its downstream consumers, and this project depends on that contract holding.
- Each source gets its own namespace under `bwr.ingest.sources.*` implementing a single `parse-record` multimethod dispatched on source keyword — new sources are additions, not edits to existing code.

---

## 7. Rule engine (`bwr.rules`)

Modeled directly on `press-logic`. Pressing rules there are "encoded as EDN data and evaluated through `core.logic` relations." This project does the same for break-window response patterns.

```clojure
(ns bwr.rules.engine
  (:require [clojure.core.logic :as l]))

(l/defrel breaks stoppage team)
(l/defrel events-in-window stoppage event)

;; A rule is a relation over the facts, not an imperative branch.
;; Every rule that fires produces its own evidence trail (the events that
;; satisfied it), which is what the UI renders next to the recommendation.
(defn break-adjacent-substitution [team stoppage event]
  (l/fresh [minute clock-minute window]
    (l/== (:event/type event) :event.type/substitution)
    (l/== (:event/team event) team)
    (events-in-window stoppage event)))
```

- Rules live as **data** (`resources/rules/*.edn`), loaded and evaluated, not hardcoded as Clojure functions scattered through the app. Adding or tuning a rule should not require a deploy of application code — it requires committing a new EDN rule and bumping `:rule/version`.
- Every fired rule must populate `:recommendation/evidence` with the exact entity IDs that satisfied it. A rule that cannot produce evidence is not permitted to produce a recommendation — enforce this in the engine, not by convention.
- No machine-learning black box anywhere in this namespace. If a future contributor wants to add a statistical model, it goes in a clearly separate `bwr.rules.experimental` namespace, is never used to generate a shown recommendation without an accompanying rule-based explanation, and requires an ADR.

---

## 8. Web layer

Server-rendered Hiccup views, Reitit routes, progressive enhancement from SSR to islands only where genuinely useful (e.g., live-updating the match timeline during an in-progress match via Server-Sent Events, matching the reference architecture's "SSR is not the opposite of live" stance).

```
GET  /matches/:id                      -> match report (SSR)
GET  /matches/:id/stoppages/:sid       -> single stoppage detail, with evidence
GET  /matches/:id/live                 -> SSE stream of new events during a live match
GET  /teams/:id/break-profile          -> aggregated, cross-match team profile
GET  /admin                            -> admin dashboard (auth-gated, see §9)
POST /admin/rules/:id/reload           -> hot-reload a rule from resources/rules/*.edn
```

- **No client-side routing.** Full-page navigations by default; a morph dispatcher (per the reference architecture) handles in-place updates only where it measurably improves perceived latency.
- **Validated forms** (admin rule tuning, match-quality overrides) re-render with inline errors from the same `clojure.spec` used at ingestion — one validation vocabulary for the whole system, not two.
- **Machine legibility:** Open Graph tags per match report, `schema.org/SportsEvent` structured data, a database-backed sitemap, conditional GET validated against the store's basis-t/tx-id (not a hand-rolled ETag).

---

## 9. Security (non-negotiable)

Security is not a section to review before ship — it is maintained continuously, and a PR that weakens any of the following does not merge, full stop, regardless of what it unblocks.

### 9.1 Boundary validation

- Every HTTP input (query params, form bodies, JSON bodies) is `clojure.spec`-validated before it reaches a handler body. Invalid input returns 400 with a structured error, never reaches business logic, never reaches a query.
- Every ingested record is `clojure.spec`-validated before storage (§6). This is the same contract as input validation — the ingestion pipeline is just another untrusted boundary.

### 9.2 AuthN/AuthZ

- Passwordless, single-use, HMAC-signed magic links for admin access, matching the reference architecture. No password store, so no password store to leak.
- Links expire on a short TTL and are single-use, enforced by a transaction that atomically checks-and-invalidates — never a read-then-write race.
- Public routes (`/matches/*`) require no auth. Admin routes (`/admin/*`) require an authenticated session, enforced by Reitit route data (`:bwr/require-auth? true`), not by scattered `if` checks in handlers.

### 9.3 Asset pipeline hardening

- Subresource Integrity (SRI) hashes on every third-party or bundled asset.
- A Content-Security-Policy that denies inline script by default; any exception is explicit, scoped, and documented in the CSP itself with a comment explaining why.
- Asset hashing for cache-busting; no unhashed, mutable asset URLs in production.

### 9.4 Network and host hardening

- Default-deny `nftables` firewall; only 443 (and 22 from an allow-listed admin IP range) open.
- `fail2ban`-style containment fed by a **security-event trail**: every failed auth attempt, rate-limit trip, and malformed-boundary rejection is logged as a first-class event, not buried in a generic access log.
- Live containment levers, matching the reference architecture: ban an IP or user, rotate a signing key with a grace window — available as admin actions, not just manual server commands.

### 9.5 Dependency and CVE hygiene

- A real CVE gate in CI: the build fails on a known-vulnerable dependency above an agreed severity threshold, not a manually-run occasional scan.
- Dependency versions pinned; upgrades are their own PRs, reviewed, never bundled silently into feature work.

### 9.6 Secrets

- No secret ever committed, including in `resources/`, test fixtures, or `.claude`/agent-tool config files. HMAC signing keys, mail credentials, etc. are environment-injected at the systemd unit level, matching the reference architecture's approach — not `.env` files checked in "temporarily."

### 9.7 The 3am runbook

- A documented, tested runbook for: "the box is under attack," "a bad rule version is producing wrong recommendations," "the store is corrupted," and "roll back a bad deploy." Written down in `docs/runbook.md` before this ships to anyone other than the author — not written retroactively after the first incident.

---

## 10. Testing strategy

| Layer | Tool | What it covers |
|---|---|---|
| Spec conformance | `clojure.spec` + `test.check` (generative) | Every ingestion boundary and every rule input, including malformed/adversarial generated data |
| Rule engine | `clojure.test` | Every rule fires on the exact fixture it's designed for, does *not* fire on adjacent near-miss fixtures, and always produces non-empty evidence |
| Ingestion pipeline | `clojure.test` | Malformed records from real (anonymized) source payloads are rejected, not silently dropped or coerced |
| Web layer / E2E | Playwright | Full match-report render, stoppage-detail drilldown, admin auth flow, live SSE update |
| Security | CI-integrated CVE scan + a manual pre-release checklist against §9 | No known-vulnerable deps; CSP/SRI present on every asset; admin routes reject unauthenticated requests |

No recommendation-generating rule merges without an accompanying test that proves both a positive and negative case. This is the same discipline `press-logic` already commits to ("every trigger can be traced back to the rule that fired") — untested rules cannot make that claim.

---

## 11. Deployment and operations

Mirrors the reference architecture's "Going Live" and "Operating" chapters directly:

- **Going live:** the XTDB/Postgres-backed store (per ADR-001) and the application run as systemd units on a single box the author controls. Caddy fronts it with automatic TLS. Deploys use a verified two-instance handoff for minimal-downtime updates — never a hard restart on the only running instance.
- **Metrics:** a hand-rolled metrics endpoint (no third-party APM), including the store's own transaction/query telemetry.
- **Alerting:** systemd + SMTP, no third-party alerting SaaS as a hard dependency.
- **Backup/restore:** drilled, not just configured — a documented, periodically-rehearsed restore that preserves full history (bi-temporal data intact), not just a "the backup file exists" check.
- **Scaling audit:** an honest, periodically-revisited note on what breaks first under load and at what point a second box becomes necessary — written down, not assumed.

---

## 12. Repository standards

The repository itself is held to the reference architecture's standard, meaning:

```
break-window-response/
├── .devcontainer/            # Docker, Caddy, Mailpit, TLS — checked in, reproducible
├── deps.edn
├── src/bwr/
│   ├── ingest/                # §6 — pipeline + per-source parsers
│   ├── rules/                 # §7 — core.logic engine + resources/rules/*.edn loader
│   ├── store/                 # XTDB/Datomic schema + queries (ADR-001)
│   ├── web/                   # Ring/Reitit/Hiccup — routes, views, forms
│   ├── auth/                  # passwordless magic-link implementation
│   ├── security/              # security-event trail, containment levers
│   └── ops/                   # metrics endpoint, alerting
├── resources/
│   └── rules/*.edn            # rules as data, versioned
├── test/bwr/                  # mirrors src/ 1:1
├── e2e/                       # Playwright specs
├── docs/
│   ├── ARCHITECTURE.md        # this document
│   ├── runbook.md             # §9.7
│   └── adr/                   # §14, one file per decision
├── .github/workflows/         # CI: strict compile, tests, CVE gate, deploy window
└── README.md                  # on-ramp identical in spirit to the reference architecture's
```

- Every namespace under `src/bwr/` has a 1:1 test namespace under `test/bwr/`. No orphaned test files, no untested namespaces.
- Docstrings on every public var. Not decoration — this is what makes the REPL-driven workflow (`(doc ...)`) actually useful, and it's what lets an agentic tool orient itself in the codebase without re-reading every implementation.
- Commit messages and PRs reference the section of this document they implement or deviate from.

---

## 13. Build sequence (for agentic tools and humans alike)

Work in this order. Do not start §N+1 until §N's tests pass — later stages depend on earlier ones being trustworthy, not just present.

1. **Devcontainer + skeleton `deps.edn`** (§4) — confirm `clj -M:dev:repl` and `(start!)` work before anything else.
2. **`bwr.store` schema** (§5, ADR-001) — the data model is the foundation; get it reviewed before building on top of it.
3. **`bwr.ingest`** (§6) — spec + pipeline + one source (FBref) end-to-end, with generative tests proving malformed data is rejected.
4. **`bwr.rules`** (§7) — one rule, fully tested (positive + negative fixtures), evidence trail verified in the test, before adding a second rule.
5. **`bwr.web`** (§8) — match report and stoppage-detail views only; no admin, no auth yet.
6. **`bwr.auth` + `bwr.security`** (§9) — before any admin route is exposed, not after.
7. **`bwr.web` admin routes** — now that auth exists.
8. **E2E suite** (§10) — covering the flows built so far.
9. **CI/CD + CVE gate** (§11) — before the first real deploy, not retrofitted after.
10. **Deploy to the box, drill backup/restore, write the runbook** (§9.7, §11) — the project is not "done" until this step has actually been performed once, not just documented as a plan.

---

## 14. Architecture Decision Records

### ADR-001: XTDB 1.x over Datomic for primary storage

**Status:** Accepted.

**Context:** The reference architecture (Parens to Production) uses Datomic + PostgreSQL as its storage layer. This project's author already has a working, tested bi-temporal pattern in `temporal-squad` built on XTDB, and this project's core value proposition (reproducible, auditable, point-in-time recommendations) is exactly the bi-temporal problem `temporal-squad` already solved.

**Decision:** Use XTDB 1.x (pinned to `1.24.4`, embedded Datalog engine with RocksDB persistence). We explicitly and intentionally choose the XTDB 1.x line over the 2.x line (which is Arrow-based, SQL-over-Postgres-wire-protocol, and cloud-object-storage oriented) because 1.x runs completely self-contained within the JVM process on "one box, owned" with zero external infrastructure dependencies, directly reusing the proven bi-temporal schemas and Datalog queries from `temporal-squad`. We document this deliberate choice so future readers understand that 1.x is selected for architectural cohesion and self-containment rather than unawareness of 2.x.

**Consequence:** Slight divergence from the reference architecture's literal stack, in service of portfolio consistency and reuse of already-proven code. Document this divergence prominently in the README so a reader of the reference book isn't confused by the substitution.

### ADR-002: No FotMob/SofaScore momentum-index scraping

**Status:** Accepted.

**Context:** valternunez/wc2026-momentum already does this well, and their own repository documents that this data is "derived only, subject to provider ToS" — i.e., even the original authors don't treat it as freely reusable.

**Decision:** This project only ingests data from sources with clean redistribution terms for derived/structured data (Wikipedia, FBref match reports). No proprietary momentum index is scraped, stored, or displayed.

**Consequence:** The system cannot show FotMob-equivalent minute-by-minute "momentum," only named, discrete events (goals, cards, subs, shots). This is treated as a feature, not a limitation — see §1.1.

---

## 15. Open questions for the author (resolved)

1. **ADR-001 confirmation:** Confirmed XTDB 1.x (1.24.4, RocksDB-backed, embedded Datalog). Clarifying rationale documented in ADR-001.
2. **Initial verified dataset:** 2026 World Cup Final + both semi-finals confirmed.
3. **Compactness/pressing metrics:** Deferred to v2 confirmed. v1 ships on goal/card/substitution/shot timing only.
