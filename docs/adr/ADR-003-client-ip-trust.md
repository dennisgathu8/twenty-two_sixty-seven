# ADR-003: Client-IP Trust, Proxy Hardening, and Edge Attribution

- **Status:** Accepted
- **Deciders:** Dennis Gathu Mwangi
- **Date:** 2026-10-07

## Context

In our architecture, the client IP address is a load-bearing security primitive used across multiple defense-in-depth controls:
1. **Rate Limiting (§9.4):** Sliding-window per-IP request throttling on public endpoints and passwordless magic link generation.
2. **Live IP Containment (§9.4, §9.7):** Dynamic runtime IP blocking (`wrap-ip-containment`) triggered during incidents.
3. **Audit Event Provenance (§9.4, §9.6):** Immutable security event logs stored in XTDB (`:sec-event/client-ip`), upholding the core project principle: *"the database is the audit log."*

Prior to this decision, the application extracted client IPs via a helper (`extract-client-ip`) that unconditionally trusted `X-Forwarded-For` and selected the leftmost entry (`(first (str/split xf #","))`). Furthermore, the server listened on `0.0.0.0:3000` (all network interfaces) by default.

This design created three critical vulnerabilities:
1. **Rate Limit and Ban Bypass:** An attacker can rotate the `X-Forwarded-For` header on every request, resetting their sliding-window bucket and bypassing both rate limits and active IP bans.
2. **Framing & Denial of Service:** An attacker can forge a coach's IP address into `X-Forwarded-For`, exhausting that coach's rate-limiting bucket until the coach is locked out.
3. **Audit Trail Poisoning:** Arbitrary strings (including script injections, hostnames, and invalid strings) were persisted into XTDB as `:sec-event/client-ip`, degrading audit integrity.

Additionally, while Caddy 2.8 may overwrite or strip incoming `X-Forwarded-For` from untrusted downstreams by default, the application cannot rely on proxy assumptions without empirical proof. If port 3000 is bound to `0.0.0.0`, any client on the local network can bypass Caddy entirely and send spoofed headers directly to the application.

## Decision

We harden client-IP handling across five structural controls:

### 1. Single Edge Middleware (`wrap-client-ip`)
We introduce a single edge middleware `wrap-client-ip` positioned as the outermost layer of the Ring stack in `create-app`. It evaluates the request once, resolves the authentic client IP, and associates it as `:bwr/client-ip`. All downstream consumers (rate limiter, IP containment, session verification, CSRF handler, admin handlers, and audit logging) consume `:bwr/client-ip`. Direct calls to `extract-client-ip` are eliminated across the codebase.

### 2. Explicit Trusted Proxy Allowlist (`BWR_TRUSTED_PROXIES`)
The application defines `BWR_TRUSTED_PROXIES`, a comma-separated list of IP literals read once at startup and validated via `clojure.spec`.
- The default value is empty (`#{}`), meaning **trust nothing by default**.
- If any configured entry is not a valid IP literal, startup fails immediately with an informative `ex-info`.
- The effective trusted proxy set is logged at startup.

### 3. Right-to-Left Traversal (Leftmost-First Rejected)
We reject the naive leftmost-first parsing algorithm:
- **Untrusted Remote:** If `:remote-addr` is not in `BWR_TRUSTED_PROXIES`, the request did not arrive via a trusted proxy. The application uses `:remote-addr` directly and ignores `X-Forwarded-For` and `X-Real-IP` completely.
- **Trusted Remote:** If `:remote-addr` is a trusted proxy, the application walks `X-Forwarded-For` from the **right** (the proxy's append side), skipping known trusted proxies, and takes the first valid untrusted IP literal. If no untrusted address is found, it checks `X-Real-IP` (if valid and untrusted), and finally falls back to `:remote-addr`.

Leftmost-first was rejected because any downstream client can prefix arbitrary entries into `X-Forwarded-For` before forwarding. The only trustworthy entries are those appended by verified proxies from the right.

### 4. Strict IP Literal Parsing Without DNS Resolution
Every candidate IP is validated as a strict IPv4 or IPv6 literal using dedicated parsing logic.
- **`InetAddress/getByName` is strictly forbidden on header text:** It performs synchronous DNS lookups on untrusted input, causing network latency, DNS leaks, and denial-of-service vulnerabilities.
- Non-IP text (such as hostnames, script tags, out-of-range numbers like `300.1.1.1`) is ignored during traversal.
- Audit events record only validated IP literals, with `"unknown"` as the fallback.

### 5. Loopback Binding Default and Test-Mode Safeguard (`BWR_BIND`)
The server default bind address is changed from `0.0.0.0` to `127.0.0.1` (`BWR_BIND`), read once at startup and spec-validated.
- Binding to a non-loopback interface emits a prominent warning banner at startup.
- **Test-Mode Safeguard:** When `BWR_ENV=test` is active, binding to a non-loopback address is strictly forbidden, and the application refuses to start. This guarantees that test-only routes (`POST /test/auth/magic-link`) and relaxed cookie flags can never be exposed to external networks.

### 6. Effective-Rate Detection for Relaxed Limits
Relaxed rate-limit detection is defined by the effective rate:
$$\text{effective rate} = \frac{\text{max-requests} \times 60}{\text{window-seconds}} > 100$$
This ensures that configurations such as `BWR_RATE_LIMIT_MAX=100 BWR_RATE_LIMIT_WINDOW_S=1` (6000 requests/min) trigger the relaxed-rate banner rather than escaping notice.

## Consequences

- Direct attacks against port 3000 cannot spoof client IPs; unforwarded requests are attributed strictly to `:remote-addr`.
- In devcontainer and production environments, Caddy and Playwright communicate via loopback (`127.0.0.1`), preserving existing developer workflows and E2E test runs without configuration changes.
- **Operational Requirement for Step 10:** In production deployment (Step 10), Caddy's upstream proxy relationship must be explicitly declared via `trusted_proxies` in the `Caddyfile`, and Caddy's header stripping behavior must be empirically verified with real requests rather than assumed from documentation.
