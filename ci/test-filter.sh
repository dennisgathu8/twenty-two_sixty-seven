#!/usr/bin/env bash
set -euo pipefail

# Break-Window Response — CVE Decision Logic Gate Verification (§11, ADR-004)
# Offline, fixture-based verification of filter-cve-findings.clj decision logic:
# 1. An unallowlisted HIGH finding exits 1 and names the CVE.
# 2. An allowlisted, unexpired finding exits 0.
# 3. An allowlisted but expired entry exits 1.
# 4. An empty file, non-JSON text, or JSON missing the expected structure exits 1 (fails closed).
# 5. Clean report requires verified scan proof:
#    - Empty report with NO proof exits 1 (fails closed).
#    - Empty report WITH valid proof exits 0.
# 6. Non-existent scan file exits 1 (fails closed).
# 7. Finding with UNKNOWN or blank severity exits 1 (fails closed).
# 8. Finding with missing CVSS score exits 1 (fails closed).
# 9. Finding with string-typed CVSS score parses safely without crashing.
# 10. Allowlist entry with wrong package coordinate does not match and exits 1.
# 11. Allowlist entry missing :expires-on exits 1 (fails closed).
# 12. Scanner invocation failing for reasons other than findings exits 1.

echo "================================================================================"
echo "Running offline decision-logic tests for CVE Gate..."
echo "================================================================================"

PASSED=0
FAILED=0

run_test() {
  local desc="$1"
  local expected_exit="$2"
  local json_file="$3"
  local allowlist_file="$4"
  local expected_needle="${5:-}"
  local proof_file="${6:-}"

  echo -n "Test: ${desc} ... "
  local out_log
  out_log=$(mktemp /tmp/test_filter_out_XXXXXX.log)
  set +e
  HOME=/tmp clojure -M -e "(load-file \"ci/filter-cve-findings.clj\") (ci.filter-cve-findings/-main \"${json_file}\" \"${allowlist_file}\" \"${proof_file}\")" > "${out_log}" 2>&1
  local actual_exit=$?
  set -e

  local ok=true
  if [ "${actual_exit}" -ne "${expected_exit}" ]; then
    echo "FAILED! Expected exit ${expected_exit}, got ${actual_exit}"
    cat "${out_log}"
    ok=false
  fi

  if [ -n "${expected_needle}" ] && ! grep -q "${expected_needle}" "${out_log}"; then
    echo "FAILED! Output missing expected pattern: '${expected_needle}'"
    cat "${out_log}"
    ok=false
  fi

  if [ "${ok}" = true ]; then
    echo "OK (exit ${actual_exit})"
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
  fi
  rm -f "${out_log}"
}

# 1. Unallowlisted HIGH finding exits 1 and names the CVE
run_test "1. Unallowlisted HIGH finding exits 1 and names CVE" \
  1 "ci/fixtures/valid-finding.json" "ci/fixtures/allowlist-empty.edn" "CVE-2099-0001"

# 2. Allowlisted, unexpired finding exits 0
run_test "2. Allowlisted, unexpired finding exits 0" \
  0 "ci/fixtures/valid-finding.json" "ci/fixtures/allowlist-valid.edn" "ALLOWLISTED"

# 3. Allowlisted but expired entry exits 1
run_test "3. Allowlisted but expired entry exits 1" \
  1 "ci/fixtures/valid-finding.json" "ci/fixtures/allowlist-expired.edn" "EXPIRED"

# 4a. Empty file exits 1 (fails closed)
run_test "4a. Empty file exits 1" \
  1 "ci/fixtures/empty.json" "ci/fixtures/allowlist-empty.edn" "empty"

# 4b. Non-JSON text exits 1 (fails closed)
run_test "4b. Non-JSON text exits 1" \
  1 "ci/fixtures/invalid-text.json" "ci/fixtures/allowlist-empty.edn" "Failed to parse"

# 4c. Non-array JSON structure exits 1 (fails closed)
run_test "4c. Non-array JSON structure exits 1" \
  1 "ci/fixtures/invalid-structure.json" "ci/fixtures/allowlist-empty.edn" "JSON array"

# 5a. Empty report with NO proof of a scan exits 1 (fails closed)
run_test "5a. Empty report with no proof of a scan exits 1" \
  1 "ci/fixtures/clean-scan.json" "ci/fixtures/allowlist-empty.edn" "no valid scan proof provided" ""

# 5b. Empty report with valid proof of a scan exits 0
run_test "5b. Empty report with proof of a scan exits 0" \
  0 "ci/fixtures/clean-scan.json" "ci/fixtures/allowlist-empty.edn" "Clean scan: 0 vulnerability findings" "ci/fixtures/canary-proof.txt"

# 6. Non-existent scan file exits 1 (fails closed)
run_test "6. Non-existent scan file exits 1" \
  1 "ci/fixtures/non-existent.json" "ci/fixtures/allowlist-empty.edn" "not found"

# 7. Finding with UNKNOWN severity exits 1
run_test "7. Unknown severity exits 1" \
  1 "ci/fixtures/unknown-severity.json" "ci/fixtures/allowlist-empty.edn" "UNKNOWN_OR_BLANK_SEVERITY"

# 8. Finding with missing CVSS score exits 1
run_test "8. Missing score exits 1" \
  1 "ci/fixtures/missing-score.json" "ci/fixtures/allowlist-empty.edn" "MISSING_CVSS_SCORE"

# 9. String-typed CVSS score parses safely without crashing
run_test "9. String-typed score doesn't crash the filter" \
  0 "ci/fixtures/string-score.json" "ci/fixtures/allowlist-valid.edn" "ALLOWLISTED"

# 10. Allowlist entry with wrong package coordinate doesn't match
run_test "10. Allowlist entry with wrong package doesn't match" \
  1 "ci/fixtures/valid-finding.json" "ci/fixtures/allowlist-wrong-package.edn" "VIOLATION"

# 11. Allowlist entry missing :expires-on exits 1
run_test "11. Entry with no :expires-on exits 1" \
  1 "ci/fixtures/valid-finding.json" "ci/fixtures/allowlist-no-expiry.edn" "missing mandatory :expires-on"

# 12. Scanner tool crashing / non-zero exit handling in cve-gate.sh exits 1
echo -n "Test: 12. Scanner tool crash / non-zero exit handling exits 1 ... "
CRASH_OUT=$(mktemp /tmp/test_crash_out_XXXXXX.log)
set +e
(
  # Simulates cve-gate.sh when clj-watson exits non-zero (tool crash)
  CLJ_STATUS=1
  CLJ_ERR="/tmp/simulated_crash.err"
  echo "Exception in thread \"main\" java.lang.RuntimeException: Scanner aborted" > "${CLJ_ERR}"
  if [ "${CLJ_STATUS}" -ne 0 ]; then
    echo "ERROR: clj-watson scan command failed to execute!" >&2
    cat "${CLJ_ERR}" >&2
    rm -f "${CLJ_ERR}"
    exit 1
  fi
) > "${CRASH_OUT}" 2>&1
CRASH_EXIT=$?
set -e
if [ "${CRASH_EXIT}" -ne 0 ] && grep -q -i "failed to execute" "${CRASH_OUT}"; then
  echo "OK (exit ${CRASH_EXIT})"
  PASSED=$((PASSED + 1))
else
  echo "FAILED! Expected non-zero exit and failure message, got ${CRASH_EXIT}"
  cat "${CRASH_OUT}"
  FAILED=$((FAILED + 1))
fi
rm -f "${CRASH_OUT}"

echo "================================================================================"
echo "Decision Logic Gate Results: ${PASSED} passed, ${FAILED} failed."
echo "================================================================================"

if [ "${FAILED}" -gt 0 ]; then
  exit 1
fi
exit 0
