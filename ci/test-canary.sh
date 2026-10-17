#!/usr/bin/env bash
set -euo pipefail

# Canary test proving that the CVE gate scanner fails on known vulnerabilities (§11, ADR-004).
# Runs clj-watson against ci/canary/deps.edn (which depends on commons-text 1.9, CVE-2022-42889).
# EXPECTS A NON-ZERO EXIT CODE. If the scanner exits 0, this test fails.

CANARY_DIR="ci/canary"
CANARY_JSON="/tmp/canary_scan.json"
CANARY_ERR="/tmp/canary_scan.err"
CANARY_OUT="/tmp/canary_pipeline.log"

echo "Running canary vulnerability scan against ${CANARY_DIR}/deps.edn..."

set +e
(cd "${CANARY_DIR}" && clojure -M:clj-watson scan -p deps.edn --database-strategy github-advisory -o json > "${CANARY_JSON}" 2> "${CANARY_ERR}")
SCAN_STATUS=$?
set -e

if [ "${SCAN_STATUS}" -ne 0 ]; then
  echo "CRITICAL: clj-watson canary scan execution failed!" >&2
  cat "${CANARY_ERR}" >&2
  exit 1
fi

echo "Canary Pass 1: Testing negative control (empty allowlist must FAIL)..."
set +e
HOME=/tmp clojure -M -e "(load-file \"ci/filter-cve-findings.clj\") (ci.filter-cve-findings/-main \"${CANARY_JSON}\" \"ci/fixtures/allowlist-empty.edn\")" > "${CANARY_OUT}" 2>&1
PASS1_STATUS=$?
set -e

if [ "${PASS1_STATUS}" -eq 0 ]; then
  echo "CRITICAL: Canary negative control failed! Pipeline unexpectedly passed on known-vulnerable fixture with empty allowlist." >&2
  cat "${CANARY_OUT}" >&2
  exit 1
fi

if ! grep -q "CVE-2022-42889" "${CANARY_OUT}"; then
  echo "CRITICAL: Canary output did not identify expected violation CVE-2022-42889!" >&2
  cat "${CANARY_OUT}" >&2
  exit 1
fi
echo "Canary Pass 1 passed: Gate correctly failed closed on unallowlisted vulnerability (exit ${PASS1_STATUS})."

echo "Canary Pass 2: Testing positive control (canary allowlist must PASS)..."
set +e
HOME=/tmp clojure -M -e "(load-file \"ci/filter-cve-findings.clj\") (ci.filter-cve-findings/-main \"${CANARY_JSON}\" \"ci/canary/allowlist.edn\")" > "${CANARY_OUT}" 2>&1
PASS2_STATUS=$?
set -e

if [ "${PASS2_STATUS}" -ne 0 ]; then
  echo "CRITICAL: Canary positive control failed! Pipeline unexpectedly failed when vulnerabilities were allowlisted." >&2
  cat "${CANARY_OUT}" >&2
  exit 1
fi

if ! grep -q "ALLOWLISTED" "${CANARY_OUT}"; then
  echo "CRITICAL: Canary positive control output missing ALLOWLISTED confirmation!" >&2
  cat "${CANARY_OUT}" >&2
  exit 1
fi
echo "Canary Pass 2 passed: Gate correctly passed on valid allowlisted exception (exit ${PASS2_STATUS})."

echo "Canary verification passed: Complete CVE gate decision pipeline successfully verified end-to-end."
exit 0
