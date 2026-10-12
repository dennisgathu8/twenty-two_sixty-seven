#!/usr/bin/env bash
set -euo pipefail

# Canary test proving that the CVE gate scanner fails on known vulnerabilities (§11, ADR-004).
# Runs clj-watson against ci/canary/deps.edn (which depends on commons-text 1.9, CVE-2022-42889).
# EXPECTS A NON-ZERO EXIT CODE. If the scanner exits 0, this test fails.

CANARY_DIR="ci/canary"
CANARY_LOG="/tmp/canary_audit.log"

echo "Running canary vulnerability scan against ${CANARY_DIR}/deps.edn..."

set +e
(cd "${CANARY_DIR}" && clojure -M:clj-watson scan -p deps.edn --database-strategy github-advisory --cvss-fail-threshold 7.0) > "${CANARY_LOG}" 2>&1
CANARY_STATUS=$?
set -e

if [ "${CANARY_STATUS}" -eq 0 ]; then
  echo "CRITICAL: Canary vulnerability check FAILED! Scanner exited with code 0 on known-vulnerable fixture." >&2
  cat "${CANARY_LOG}" >&2
  exit 1
fi

echo "Canary verification passed: Scanner correctly failed (exit code ${CANARY_STATUS}) on known vulnerability."
echo "Canary findings observed:"
grep -E "CVE-|GHSA-|Vulnerability|commons-text" "${CANARY_LOG}" || head -n 20 "${CANARY_LOG}"
exit 0
