# ADR-002: No FotMob/SofaScore Momentum-Index Scraping

- **Status:** Accepted
- **Deciders:** Dennis Gathu Mwangi
- **Date:** 2026-09-07

## Context

Prior tournament research (such as `valternunez/wc2026-momentum`) examined tournament-wide causal impacts using proprietary momentum widgets (FotMob/SofaScore). However, those indices:
1. Are governed by restrictive Terms of Service that treat them as derived-only and non-redistributable.
2. Function as opaque, algorithmic black boxes whose internal scoring formulas cannot be explained or independently audited by a team analyst or coaching staff.

## Decision

This project strictly ingests data from sources with clean redistribution terms for derived and structured match data (Wikipedia and FBref match reports). No proprietary momentum index will be scraped, stored, or displayed.

All decision-support signals are constructed purely from discrete, named, auditable events:
- Goal timing
- Yellow and red card timing
- Substitution timing (player on / player off)
- Shot timing

## Consequences

- The system does not attempt minute-by-minute continuous "momentum" graphs.
- Every insight presented to a coach is 100% auditable down to the exact second/minute and event entity ID in the match record.
