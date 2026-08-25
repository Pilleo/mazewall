#!/usr/bin/env bash
# Inner container script for Tier E WP-03 Gate G0a.
# Expects /work mounted on ebpf-prototype/ with prebuilt artifacts.
set -uo pipefail

DRIVER_PID_FILE=/tmp/tier_e_wp03_driver.pid

./build/wp03_driver 1000000 &
DRIVER=$!
echo "$DRIVER" > "$DRIVER_PID_FILE"

sleep 0.15
./build/wp03_loader --pid "$DRIVER" --marker ./build/libmazewall_context.so --duration 8
LOADER_RC=$?

wait "$DRIVER" 2>/dev/null
exit "$LOADER_RC"
