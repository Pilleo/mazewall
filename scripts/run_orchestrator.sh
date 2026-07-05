#!/bin/bash
# scripts/run_orchestrator.sh
# Runs the autonomous backlog orchestrator daemon

# Ensure logs directory exists
mkdir -p logs

if [ "$1" == "--background" ]; then
    echo "🚀 Starting Orchestrator Daemon in background..."
    nohup ./gradlew :tools:orchestrator:run > logs/orchestrator.log 2>&1 &
    echo "   Logs redirected to: logs/orchestrator.log"
    echo "   PID: $!"
else
    echo "⚡ Starting Orchestrator Daemon in foreground..."
    ./gradlew :tools:orchestrator:run
fi
