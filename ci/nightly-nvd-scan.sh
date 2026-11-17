#!/usr/bin/env bash
set -euo pipefail

# Break-Window Response — Nightly Deep NIST NVD Security Audit Gate (§9.5, §11, ADR-004)
# Executes clj-watson with --database-strategy dependency-check and routes output through
# ci/filter-cve-findings.clj against .github/cve-allowlist.edn, aligning gating policy with CI.
# Requires NVD_API_KEY secret. Skips cleanly if the secret is not provided.

ALLOWLIST_FILE=".github/cve-allowlist.edn"
CANARY_PROOF="/tmp/canary_scan_proof.txt"

if [ -z "${CLJ_WATSON_NVD_API_KEY:-}" ]; then
  echo "================================================================================"
  echo "NOTICE: Repository secret NVD_API_KEY is not configured or empty."
  echo "Skipping deep NIST NVD scan. To enable, add NVD_API_KEY in repository secrets."
  echo "================================================================================"
  exit 0
fi

echo "================================================================================"
echo "Executing nightly deep NIST NVD dependency scan..."
echo "NVD_API_KEY detected in environment (length: ${#CLJ_WATSON_NVD_API_KEY} chars). Passing to clj-watson."
echo "================================================================================"

echo "Step 1: Validating CVE allowlist integrity and expiration dates..."
clojure -M -e "(load-file \"ci/validate-allowlist.clj\") (ci.validate-allowlist/-main \"${ALLOWLIST_FILE}\")"

echo ""
echo "Step 2: Verifying project dependency graph is non-empty..."
DEP_COUNT=$(HOME=/tmp clojure -Stree | grep -E -c -v '^[[:space:]]*$' || true)
if [ "${DEP_COUNT}" -eq 0 ]; then
  echo "ERROR: Dependency graph is empty (0 dependencies found)! Refusing to run NVD scan." >&2
  exit 1
fi
echo "Discovered ${DEP_COUNT} dependencies in project classpath graph."

echo ""
echo "Step 3: Running clj-watson deep NIST NVD audit (dependency-check strategy)..."
NVD_JSON="/tmp/nvd_scan.json"
NVD_ERR="/tmp/nvd_scan.err"
set +e
clojure -M:clj-watson scan -p deps.edn --database-strategy dependency-check -o json > "${NVD_JSON}" 2> "${NVD_ERR}"
NVD_STATUS=$?
set -e

if [ "${NVD_STATUS}" -ne 0 ]; then
  echo "ERROR: clj-watson NVD scan command failed to execute!" >&2
  cat "${NVD_ERR}" >&2
  cat "${NVD_JSON}" >&2
  exit 1
fi

echo "clj-watson NVD scanner execution completed successfully. Scanner logs (last 20 lines):"
tail -n 20 "${NVD_ERR}"

echo ""
echo "Step 4: Evaluating NVD scan report against gating rules and allowlist..."
clojure -M -e "(load-file \"ci/filter-cve-findings.clj\") (ci.filter-cve-findings/-main \"${NVD_JSON}\" \"${ALLOWLIST_FILE}\" \"${CANARY_PROOF}\")"

echo "================================================================================"
echo "Nightly NIST NVD dependency scan PASSED: zero unapproved HIGH/CRITICAL CVEs."
echo "================================================================================"
exit 0
