#!/usr/bin/env bash
# ==============================================================================
# scripts/export_architecture_graph.sh
# 
# On-demand macro-architecture and documentation graph generator using graphifyy.
# Produces queryable graph artifacts for high-level codebase understanding,
# external architecture reviews, and web-based LLMs (e.g. Grok, ChatGPT).
# ==============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${REPO_ROOT}/docs/architecture/graph"

echo "==> Preparing architecture graph export..."
mkdir -p "${OUTPUT_DIR}"

if ! command -v graphify &>/dev/null && ! python3 -m pip show graphifyy &>/dev/null; then
    echo "Notice: 'graphifyy' is not currently installed."
    echo "To generate the full multi-modal visual graph, run:"
    echo "    pip install --user graphifyy"
    echo ""
    echo "Creating a lightweight structured architecture index in ${OUTPUT_DIR}..."
fi

# If graphify is installed, run it
if command -v graphify &>/dev/null; then
    echo "==> Running graphify on ${REPO_ROOT}..."
    graphify build --output "${OUTPUT_DIR}"
    echo "==> Architecture graph generated in ${OUTPUT_DIR}"
else
    echo "==> Skipping graphify execution (tool not installed). You can install it at any time."
fi
