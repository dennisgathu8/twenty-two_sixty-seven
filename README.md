# Break-Window Response (`22-67`)

> Decision support for FIFA World Cup 2026 mandatory hydration breaks (22' and 67'), expressed as auditable, inspectable rules.

**Stack Lineage:** `pitch-pipe` → `temporal-squad` → `press-logic` → `formation-stream` → **`break-window-response`**  
**Architectural Model:** [Parens to Production](https://mapidentity.github.io/parens-to-production/) — server-rendered, framework-free Clojure/XTDB SaaS, built, operated, and defended on one box you own.

---

## 1. Quick Start

### Devcontainer (Recommended)

Open this repository in VS Code or any devcontainer-compatible environment. It builds on Debian 12 with explicitly pinned **JDK 17**, **Node.js 22 (LTS)**, Clojure CLI, Caddy (for local TLS at `https://breakwindow.lan`), and Mailpit, all verified against hardcoded SHA-256 checksums and configured with `C.UTF-8` locale.

### Running via REPL

Inside the container or host:

```bash
# Start an nREPL session with dev tooling
clj -M:dev:repl

# In the REPL (dev/user.clj):
user=> (start!)
# Break-Window Response system started successfully.
# HTTP server listening at http://localhost:3000 and https://breakwindow.lan

user=> (stop!)
user=> (restart!)
```

### Running Standalone Server (CLI)

```bash
# Start standalone server and seed verified tournament fixtures (M42 France vs Iraq)
clojure -M -m bwr.main --seed
```

### Running Tests

```bash
# Run Clojure unit, boundary, and generative tests (52 tests / 596 assertions)
clj -M:test

# Run headless browser Playwright E2E test suite (10 specs)
npx playwright test

# Run repeated E2E stress test (30 specs, verifying rate-limiter and test isolation)
npx playwright test --repeat-each=3
```

### Reflection & Strict Compilation Check

```bash
# Run the strict compilation gate (loads all 20 bwr/ namespaces with *warn-on-reflection* true)
bash ci/compile-gate.sh
```

### Security & CI Verification Scripts

```bash
# Run shellcheck over all CI scripts
shellcheck ci/*.sh

# Run offline fixture-based verification of the CVE gate decision logic (18 tests)
bash ci/test-filter.sh

# Run the canary test proving the CVE gate fails on known vulnerabilities
bash ci/test-canary.sh

# Run the GitHub Advisory CVE audit against deps.edn
bash ci/cve-gate.sh github-advisory
```

---

## 2. Configuration & Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `BWR_BIND` | `127.0.0.1` | Local IP literal address to bind http-kit server. Validated with `clojure.spec`. Emits loud warning if non-loopback; refuses startup when `BWR_ENV=test` is active (§9.4, ADR-003). |
| `BWR_TRUSTED_PROXIES` | *(empty)* | Comma-separated list of trusted upstream proxy IP literals. Validated with `clojure.spec`. When empty, forwarding headers are ignored and `:remote-addr` is used directly (ADR-003). |
| `BWR_ENV` | *(unset)* | Set to `test` to mount the test magic-link minting endpoint (`POST /test/auth/magic-link`) and relax the session cookie `Secure` flag for local HTTP testing. **Must never run in production (§9, §10).** |
| `BWR_RATE_LIMIT_MAX` | `100` | Sliding-window maximum request count per client IP. Validated with `clojure.spec` (`pos-int?`). Configured to `10000` during Playwright test runs. Loud warning banner triggers if effective rate `(max * 60 / window) > 100`. |
| `BWR_RATE_LIMIT_WINDOW_S` | `60` | Duration of the rate limiter sliding window in seconds. Validated with `clojure.spec` (`pos-int?`). |
| `LANG` / `LC_ALL` | `C.UTF-8` | Container locale ensuring UTF-8 encoding across stdout, logging banners, and disk I/O. |

---

## 3. Architecture & Design Principles

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the governing specification.

1. **No framework where a library will do:** Ring + http-kit + Reitit-Ring + Hiccup.
2. **Spec at the boundary:** All ingestion is validated via `clojure.spec.alpha` before touching business logic.
3. **Traceability:** Every recommendation links directly to the rule and underlying match event IDs in `:recommendation/evidence`.
4. **Scanner-Safe Authentication (§9.2):** Magic links render a confirmation step (`GET /auth/verify`) before atomic token consumption (`POST /auth/verify`), preventing enterprise email scanners from burning single-use tokens.
5. **XTDB 1.x Bi-temporal Storage ([ADR-001](docs/adr/ADR-001-xtdb-primary-storage.md)):** Deliberately pinned to XTDB 1.x (embedded, Datalog, RocksDB-backed) to match `temporal-squad`'s proven bi-temporal pattern and keep the architecture 100% self-contained on a single box.
6. **Discrete Events over Black Boxes ([ADR-002](docs/adr/ADR-002-no-fotmob-scraping.md)):** Strict ingestion from ToS-clean sources (Wikipedia, FBref); no scraping of third-party proprietary momentum widgets.
7. **Edge Client-IP Attribution ([ADR-003](docs/adr/ADR-003-client-ip-trust.md)):** `wrap-client-ip` edge middleware, loopback default binding, right-to-left header traversal skipping trusted proxies, and DNS-free IP parsing.
8. **Supply Chain Defense & CI Verification Gate ([ADR-004](docs/adr/ADR-004-ci-and-cve-gate.md)):** Strict compile gate (zero reflection warnings in `bwr/`), automated CVE audits with zero accepted vulnerabilities (`cve-allowlist.edn`), isolated Shellcheck lint job, and proof-of-scan verification across CI and Nightly NVD workflows.

---

## 4. Directory Layout

```
├── .devcontainer/            # Docker (JDK 17, Node 22, Caddy, Mailpit), pinned SHA-256s, devcontainer.json
├── .github/
│   ├── workflows/            # GitHub Actions (ci.yml, nightly.yml, devcontainer.yml)
│   ├── cve-allowlist.edn     # CVE exception allowlist (empty, zero accepted risks)
│   └── dependabot.yml        # Dependabot configuration (github-actions, npm, docker)
├── ci/                       # CI gates, lint, canary verification & CVE filtering scripts
│   ├── compile-gate.sh       # Reflection & strict compilation check
│   ├── cve-gate.sh           # Main supply-chain CVE gate
│   ├── nightly-nvd-scan.sh   # Deep NIST NVD vulnerability scan
│   ├── test-canary.sh        # Scanner canary fixture verification
│   ├── test-filter.sh        # Offline CVE decision-logic test suite (18 tests)
│   ├── filter-cve-findings.clj # Authoritative CVE policy evaluation & proof of scan
│   └── validate-allowlist.clj  # Allowlist schema & expiration validation
├── deps.edn                  # Pinned dependencies, :dev, :test, :clj-watson, :repl aliases
├── package.json              # Pinned Playwright test runner (@playwright/test 1.63.0)
├── package-lock.json         # Pinned npm dependencies lockfile
├── playwright.config.js      # Playwright E2E configuration & webServer lifecycle
├── e2e/                      # Playwright end-to-end browser specs (§10)
│   ├── match_report.spec.js  # Public SSR views, hydration break windows, and evidence trails
│   └── admin_ops.spec.js     # Auth flow, token replay rejection, CSRF forms, rule reload & overrides
├── src/bwr/
│   ├── main.clj              # System lifecycle, CLI entry point, rate limiter env configuration
│   ├── ingest/               # Spec validation + transducer ingestion pipelines
│   ├── rules/                # core.logic rule engine + resources/rules/*.edn loader
│   ├── store/                # XTDB bi-temporal schema + queries (ADR-001)
│   ├── web/                  # Reitit-Ring routes, Hiccup SSR views, session auth & cookie handling
│   ├── auth/                 # Passwordless magic-link authentication & email delivery
│   └── security/             # Security middleware, event trail, rate-limiting, IP containment
├── dev/
│   └── user.clj              # REPL user namespace with (start!), (stop!)
├── resources/
│   ├── public/css/main.css   # Main stylesheet with Subresource Integrity (SRI) hashing
│   └── rules/*.edn           # Versioned rules as EDN data
├── test/bwr/                 # 1:1 test suites mirroring src/bwr/
├── docs/
│   ├── ARCHITECTURE.md       # Governing architecture specification (Steps 1–10)
│   ├── runbook.md            # Operations runbook (§9.7, §11 — Step 10)
│   └── adr/                  # Architectural Decision Records (ADR-001, ADR-002, ADR-003, ADR-004)
└── README.md
```

---

## 5. Build Sequence Status

Defined in [docs/ARCHITECTURE.md §13](docs/ARCHITECTURE.md#13-build-sequence-for-agentic-tools-and-humans-alike):

* [x] **Step 1:** Devcontainer + skeleton `deps.edn` (§4, §12)
* [x] **Step 2:** `bwr.store` schema (ADR-001)
* [x] **Step 3:** `bwr.ingest` pipeline (§6)
* [x] **Step 4:** `bwr.rules` engine (§7)
* [x] **Step 5:** `bwr.web` public views (§8)
* [x] **Step 6:** `bwr.auth` + `bwr.security` (§9)
* [x] **Step 7:** `bwr.web` admin routes (§8, §9)
* [x] **Step 8:** E2E Playwright test suite (§10)
* [x] **Step 9:** CI/CD + CVE gate (§11)
* [ ] **Step 10:** Deploy to the box, drill backup/restore, write the runbook (§9.7, §11)
