#!/usr/bin/env bash
# Inner container script for the Tier E collector kernel phase (WP-02).
# Runs INSIDE a rootful container started by run_collector.sh; expects:
#   - /work mounted on ebpf-prototype/ (prebuilt build/tier_e_collector)
#   - DURATION env var (seconds)
set -euo pipefail

bash -c '
  for i in $(seq 1 200); do
    echo "probe-$i" > "/tmp/tier_e_wp02_$i"
    cat "/tmp/tier_e_wp02_$i" > /dev/null
    sleep 0.05
  done
' &
WORKLOAD=$!
sleep 0.3

./build/tier_e_collector --pid "$WORKLOAD" --duration "${DURATION:-5}" || true

wait "$WORKLOAD" 2>/dev/null || true
