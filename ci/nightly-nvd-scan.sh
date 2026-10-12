#!/usr/bin/env bash
set -euo pipefail

# Nightly deep NVD vulnerability scan using clj-watson dependency-check strategy (§9.5, §11, ADR-004).
# Requires NVD_API_KEY secret. Skips cleanly if the secret is not provided.

if [ -z "${CLJ_WATSON_NVD_API_KEY:-}" ]; then
  echo "================================================================================"
  echo "NOTICE: Repository secret NVD_API_KEY is not configured or empty."
  echo "Skipping deep NIST NVD scan. To enable, add NVD_API_KEY in repository secrets."
  echo "================================================================================"
  exit 0
fi

echo "================================================================================"
echo "Executing nightly deep NIST NVD dependency scan..."
echo "================================================================================"

NVD_LOG="/tmp/nvd_audit.log"
set +e
clojure -M:clj-watson --database-strategy dependency-check --cvss-fail-threshold 7.0 -f > "${NVD_LOG}" 2>&1
NVD_STATUS=$?
set -e

if [ "${NVD_STATUS}" -ne 0 ]; then
  echo "ERROR: Nightly NIST NVD scan detected HIGH or CRITICAL vulnerabilities!" >&2
  cat "${NVD_LOG}" >&2
  exit 1
fi

echo "Nightly NIST NVD dependency scan PASSED: zero unapproved HIGH/CRITICAL CVEs."
exit 0
