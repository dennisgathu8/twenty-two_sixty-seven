#!/usr/bin/env bash
set -euo pipefail

# Compile gate: loads all bwr namespaces under src/ and gates on reflection warnings (§11, §12).
# Fails if:
# 1. Clojure compilation exits non-zero.
# 2. Sentinel 'ALL_BWR_NAMESPACES_LOADED' is missing from the output log.
# 3. Any reflection warning originates from a bwr/ file (^Reflection warning, bwr/).
# Note: External warnings (such as XTDB's xtdb/memory.clj warning) are kept strictly out of scope.

LOG_FILE="/tmp/bwr_compile.log"
SENTINEL="ALL_BWR_NAMESPACES_LOADED"

echo "Discovering application namespaces under src/bwr/..."
NAMESPACES=$(find src/bwr -name "*.clj" | sed -e 's|^src/||' -e 's|\.clj$||' -e 's|/|.|g' -e 's|_|-|g' | sort)

if [ -z "$NAMESPACES" ]; then
  echo "ERROR: No Clojure source files discovered under src/bwr/." >&2
  exit 1
fi

NAMESPACE_COUNT=$(echo "$NAMESPACES" | wc -l)
echo "Discovered ${NAMESPACE_COUNT} namespaces. Loading with *warn-on-reflection* true..."

# Run Clojure, capturing all stdout and stderr to LOG_FILE, and capture exit status
set +e
clojure -M -e "(set! *warn-on-reflection* true) (doseq [n '($NAMESPACES)] (require n)) (println \"$SENTINEL\")" > "$LOG_FILE" 2>&1
CLJ_STATUS=$?
set -e

# Failure Mode 1: Clojure process returned non-zero exit code
if [ "$CLJ_STATUS" -ne 0 ]; then
  echo "ERROR: Compilation process exited with non-zero status ($CLJ_STATUS)." >&2
  cat "$LOG_FILE" >&2
  exit 1
fi

# Failure Mode 2: Compilation terminated early before reaching sentinel
if ! grep -q "^$SENTINEL" "$LOG_FILE"; then
  echo "ERROR: Compilation sentinel '$SENTINEL' not found in output log." >&2
  cat "$LOG_FILE" >&2
  exit 1
fi

# Failure Mode 3: Reflection warning detected under bwr/ namespaces
if grep -E "^Reflection warning, bwr/" "$LOG_FILE"; then
  echo "ERROR: Reflection warning detected in bwr/ codebase. Reflection is forbidden under bwr/ (§11)." >&2
  exit 1
fi

echo "Compile gate passed: ${NAMESPACE_COUNT} namespaces loaded cleanly with zero bwr/ reflection warnings."
exit 0
