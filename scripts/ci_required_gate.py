#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Evaluate CI prerequisites; run python3 scripts/ci_required_gate.py --help."""

import argparse
from enum import Enum
from typing import assert_never


class Lane(Enum):
    CORE = "core-quality"
    MUTATION = "mutation-tests"
    SECURITY = "security-check"
    BUILD_LOGIC = "build-logic-validation"
    GO = "go-validation"
    OPTIONAL_INTEGRATIONS = "optional-integrations"
    CONFINED_HELPER = "confined-helper-kernel"


class Result(Enum):
    SUCCESS = "success"
    FAILURE = "failure"
    CANCELLED = "cancelled"
    SKIPPED = "skipped"
    MISSING = ""
    UNKNOWN = "unknown"


class Outcome(Enum):
    REQUIRED_SUCCESS = "required-success"
    OPTIONAL_SKIP = "optional-skip"
    REJECTED = "rejected"


def evaluate(lane: Lane, result: Result, go_enabled: bool) -> Outcome:
    """Every lane requires success except skipped Go with both opt-ins disabled."""
    match result:
        case Result.SUCCESS:
            return Outcome.REQUIRED_SUCCESS
        case Result.SKIPPED:
            return Outcome.OPTIONAL_SKIP if lane is Lane.GO and not go_enabled else Outcome.REJECTED
        case Result.FAILURE | Result.CANCELLED | Result.MISSING | Result.UNKNOWN:
            return Outcome.REJECTED
        case unreachable:
            assert_never(unreachable)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for lane in Lane:
        parser.add_argument(f"--{lane.value}", required=True)
    parser.add_argument("--go-variable", required=True)
    parser.add_argument("--go-input", required=True)
    arguments = parser.parse_args()
    go_enabled = arguments.go_variable == "true" or arguments.go_input == "true"
    outcomes: list[Outcome] = []
    for lane in Lane:
        raw: str = getattr(arguments, lane.value.replace("-", "_"))
        try:
            result = Result(raw)
        except ValueError:
            result = Result.UNKNOWN
        outcome = evaluate(lane, result, go_enabled)
        outcomes.append(outcome)
        print(f"{lane.value}: result={result.value or 'missing'} outcome={outcome.value}")
    return int(Outcome.REJECTED in outcomes)


if __name__ == "__main__":
    raise SystemExit(main())
