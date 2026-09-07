# ADR-001: XTDB 1.x over Datomic for Primary Storage

- **Status:** Accepted
- **Deciders:** Dennis Gathu Mwangi
- **Date:** 2026-09-07

## Context

The reference architecture ([Parens to Production](https://mapidentity.github.io/parens-to-production/)) uses Datomic + PostgreSQL as its primary storage layer.
In our stack lineage (`pitch-pipe` → `temporal-squad` → `press-logic` → `formation-stream` → `break-window-response`), `temporal-squad` established a tested, working bi-temporal pattern using XTDB.

Our core value proposition is coach decision support with full auditability:
- What did the coaching staff know, and what rule version was active, at the exact moment a recommendation fired?
- Being able to query against `valid-time` and `tx-time` as native primitives, without custom historical log greps.

## Decision

We use **XTDB 1.x** (pinned to `1.24.4`, embedded Datalog with local RocksDB persistence).

We explicitly and intentionally choose the **XTDB 1.x line** over the **XTDB 2.x line** (which is Arrow-based, SQL-over-Postgres-wire-protocol, and cloud-object-storage oriented).
Rationale for this deliberate choice:
1. **Self-Contained Embedded Node ("One Box, Owned"):** XTDB 1.x embeds directly inside the application JVM process, storing indices and documents locally via RocksDB. It requires no external transactor daemon, no managed cloud object store, and no external database server to run.
2. **Native Clojure Datalog & EDN:** 1.x natively consumes Clojure maps and supports pure Datalog queries with logic variables, directly mirroring our `core.logic` rule philosophy.
3. **Proven Stack Lineage:** We reuse the tested bi-temporal schema and transaction mechanics already proven in `temporal-squad`.

Even though XTDB 2.x is actively developed, 1.x is deliberately selected because its operational footprint and embedded Datalog design fit the "single box you own" and framework-free Clojure philosophy of this system.

## Consequences

- Divergence from the reference architecture's literal Datomic + Postgres pairing. This divergence is documented in `README.md` and `docs/ARCHITECTURE.md`.
- Operations (backup, restore, systemd unit) target the embedded XTDB RocksDB store and JVM process rather than an external Datomic transactor.
