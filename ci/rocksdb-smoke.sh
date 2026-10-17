#!/usr/bin/env bash
set -euo pipefail

# Break-Window Response — RocksDB Persistence Smoke Test (§9.5, §10, §11)
# Verifies that XTDB running with RocksDB persistence and Nippy compression
# seeds correctly, shuts down cleanly, and recovers persisted data upon restart.

echo "================================================================================"
echo "Starting RocksDB persistence smoke test..."
echo "================================================================================"

TMP_DATA=$(mktemp -d /tmp/bwr_rocksdb_smoke_XXXXXX)
SMOKE_PORT=3333
SERVER_PID=""

cleanup() {
  if [ -n "${SERVER_PID}" ] && kill -0 "${SERVER_PID}" 2>/dev/null; then
    echo "Stopping server process (PID ${SERVER_PID})..."
    kill -TERM "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
  fi
  rm -rf "${TMP_DATA}"
}
trap cleanup EXIT INT TERM

wait_for_server() {
  local max_attempts=30
  local attempt=1
  while [ "${attempt}" -le "${max_attempts}" ]; do
    if curl -s -f "http://127.0.0.1:${SMOKE_PORT}/" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    attempt=$((attempt + 1))
  done
  echo "ERROR: Server failed to start on port ${SMOKE_PORT} within ${max_attempts} seconds." >&2
  return 1
}

# ------------------------------------------------------------------------------
# Phase 1: Start with --rocksdb and --seed, verify match M42 is served
# ------------------------------------------------------------------------------
echo ""
echo "Phase 1: Starting server with --rocksdb and --seed in ${TMP_DATA}..."
BWR_DATA_DIR="${TMP_DATA}" HOME=/tmp clojure -M -m bwr.main --rocksdb --seed --port "${SMOKE_PORT}" --bind "127.0.0.1" &
SERVER_PID=$!

echo "Waiting for Phase 1 server (PID ${SERVER_PID}) to accept connections..."
wait_for_server

echo "Querying match report M42 from seeded RocksDB..."
PHASE1_RESP=$(curl -s -f "http://127.0.0.1:${SMOKE_PORT}/matches/M42")
if ! echo "${PHASE1_RESP}" | grep -q "France"; then
  echo "ERROR: Match report M42 did not contain expected content 'France'!" >&2
  echo "${PHASE1_RESP}" | head -n 30 >&2
  exit 1
fi
echo "Phase 1 success: M42 served cleanly from RocksDB store."

echo "Stopping Phase 1 server..."
kill -TERM "${SERVER_PID}"
wait "${SERVER_PID}" 2>/dev/null || true
SERVER_PID=""
sleep 2

# ------------------------------------------------------------------------------
# Phase 2: Start WITHOUT --seed, verify match M42 persisted across restart
# ------------------------------------------------------------------------------
echo ""
echo "Phase 2: Restarting server with --rocksdb WITHOUT --seed from ${TMP_DATA}..."
BWR_DATA_DIR="${TMP_DATA}" HOME=/tmp clojure -M -m bwr.main --rocksdb --port "${SMOKE_PORT}" --bind "127.0.0.1" &
SERVER_PID=$!

echo "Waiting for Phase 2 server (PID ${SERVER_PID}) to accept connections..."
wait_for_server

echo "Querying match report M42 to verify persistence across restart..."
PHASE2_RESP=$(curl -s -f "http://127.0.0.1:${SMOKE_PORT}/matches/M42")
if ! echo "${PHASE2_RESP}" | grep -q "France"; then
  echo "ERROR: Match report M42 was NOT found after unseeded restart!" >&2
  echo "${PHASE2_RESP}" | head -n 30 >&2
  exit 1
fi
echo "Phase 2 success: M42 persisted across process restart and was served cleanly."

echo "Stopping Phase 2 server..."
kill -TERM "${SERVER_PID}"
wait "${SERVER_PID}" 2>/dev/null || true
SERVER_PID=""

echo ""
echo "================================================================================"
echo "RocksDB persistence smoke test PASSED: Data verified across cold restart."
echo "================================================================================"
exit 0
