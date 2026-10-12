#!/usr/bin/env bash
set -euo pipefail

# Guard against accidental leakage of test-environment or relaxed rate-limit variables (§9.2, §10, §11).
# These variables MUST strictly appear ONLY in the E2E browser job.

if [ -n "${BWR_ENV:-}" ]; then
  echo "CRITICAL: BWR_ENV is set to '${BWR_ENV}' in a non-E2E job! Leaking test-mode environment variables is strictly forbidden." >&2
  exit 1
fi

if [ -n "${BWR_RATE_LIMIT_MAX:-}" ]; then
  echo "CRITICAL: BWR_RATE_LIMIT_MAX is set to '${BWR_RATE_LIMIT_MAX}' in a non-E2E job! Relaxed rate limit variables must only exist in E2E." >&2
  exit 1
fi

if [ -n "${BWR_RATE_LIMIT_WINDOW_S:-}" ]; then
  echo "CRITICAL: BWR_RATE_LIMIT_WINDOW_S is set to '${BWR_RATE_LIMIT_WINDOW_S}' in a non-E2E job!" >&2
  exit 1
fi

echo "Environment guard check passed: zero test-mode or rate-limit variables present."
exit 0
