# Break-Window Response (`22-67`)

> Decision support for FIFA World Cup 2026 mandatory hydration breaks (22' and 67'), expressed as auditable, inspectable rules.

**Stack Lineage:** `pitch-pipe` → `temporal-squad` → `press-logic` → `formation-stream` → **`break-window-response`**  
**Architectural Model:** [Parens to Production](https://mapidentity.github.io/parens-to-production/) — server-rendered, framework-free Clojure/XTDB SaaS, built, operated, and defended on one box you own.

---

## 1. Quick Start

### Devcontainer (Recommended)

Open this repository in VS Code or any devcontainer-compatible environment. It builds on Debian 12 with explicitly pinned **JDK 17**, Clojure CLI, Caddy (for local TLS at `https://breakwindow.lan`), and Mailpit.

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

### Running Tests

```bash
# Run unit and generative tests
clj -M:test
```

### Reflection & Strict Compilation Check

```bash
clojure -M -e "(set! *warn-on-reflection* true) (require 'bwr.main :reload)"
```

---

## 2. Architecture & Design Principles

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the governing specification.

1. **No framework where a library will do:** Ring + http-kit + Reitit + Hiccup.
2. **Spec at the boundary:** All ingestion is validated via `clojure.spec.alpha` before touching business logic.
3. **Traceability:** Every recommendation links directly to the rule and underlying match event IDs in `:recommendation/evidence`.
4. **XTDB 1.x Bi-temporal Storage ([ADR-001](docs/adr/ADR-001-xtdb-primary-storage.md)):** Deliberately pinned to XTDB 1.x (embedded, Datalog, RocksDB-backed) to match `temporal-squad`'s proven bi-temporal pattern and keep the architecture 100% self-contained on a single box.
5. **Discrete Events over Black Boxes ([ADR-002](docs/adr/ADR-002-no-fotmob-scraping.md)):** Strict ingestion from ToS-clean sources (Wikipedia, FBref); no scraping of third-party proprietary momentum widgets.

---

## 3. Directory Layout

```
├── .devcontainer/            # Docker (JDK 17), Caddy (TLS), Mailpit, devcontainer.json
├── deps.edn                  # Pinned dependencies, :dev, :test, :repl aliases
├── src/bwr/
│   ├── main.clj              # System lifecycle: (start!), (stop!), (restart!)
│   ├── ingest/               # Spec validation + transducer ingestion pipelines
│   ├── rules/                # core.logic rule engine + resources/rules/*.edn loader
│   ├── store/                # XTDB bi-temporal schema + queries (ADR-001)
│   ├── web/                  # Reitit routes, Hiccup SSR views
│   ├── auth/                 # Passwordless magic-link authentication
│   ├── security/             # Security middleware, event trail, rate-limiting
│   └── ops/                  # Metrics, alerts, telemetry
├── dev/
│   └── user.clj              # REPL user namespace with (start!), (stop!)
├── resources/
│   └── rules/*.edn           # Versioned rules as EDN data
├── test/bwr/                 # 1:1 test suites mirroring src/bwr/
├── docs/
│   ├── ARCHITECTURE.md       # Governing architecture document
│   ├── runbook.md            # Operations runbook
│   └── adr/                  # Architectural Decision Records
└── README.md
```
