#!/usr/bin/env python3
"""Validate the canonical hardening manifest and local documentation link targets."""

import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    manifest = root / "docs/internals/designs/core/post-review-hardening-evidence.md"
    content = manifest.read_text()
    errors = []
    revisions = set()
    tracked = subprocess.check_output(["git", "ls-files"], cwd=root, text=True).splitlines()
    tests = {}
    for name in tracked:
        path = Path(name)
        if path.suffix in (".kt", ".java") and "tools" not in path.parts:
            tests.setdefault(path.stem, []).append(root / path)

    for prefix in ("PRH", "RH"):
        rows = [line for line in content.splitlines() if line.startswith(f"| {prefix}-")]
        ids = [row.split("|")[1].strip() for row in rows]
        expected = [f"{prefix}-{number:02d}" for number in range(1, 31)]
        if ids != expected:
            errors.append(f"{prefix}: expected exactly 30 unique, ordered finding IDs")
        for row in rows:
            cells = [cell.strip() for cell in row.split("|")[1:-1]]
            if len(cells) != 6 or not all(cells):
                errors.append(f"Malformed evidence row: {row}")
                continue
            row_id, _, commits, evidence, _, _ = cells
            found = re.findall(r"`([0-9a-f]{7,40})`", commits)
            if not found:
                errors.append(f"{row_id}: no implementing commit")
            revisions.update(found)
            if prefix == "PRH":
                continue  # Historical test names belong to the prior implementation range.
            current_sources = []
            for token in re.findall(r"`([^`]+)`", evidence):
                class_name, separator, method = token.partition("#")
                if class_name:
                    current_sources = tests.get(class_name, [])
                    if not current_sources:
                        errors.append(f"{row_id}: missing regression class {class_name}")
                if separator and not any(method in path.read_text() for path in current_sources):
                    errors.append(f"{row_id}: missing regression method {method}")

    for revision in sorted(revisions):
        result = subprocess.run(
            ["git", "cat-file", "-e", f"{revision}^{{commit}}"], cwd=root, capture_output=True
        )
        if result.returncode:
            errors.append(f"Missing implementing commit {revision}")

    documents = list((root / "docs/internals/designs/core").glob("*.md"))
    documents.extend((root / "docs/internals/backlog").rglob("*20260910-225*.md"))
    for path in documents:
        for target in re.findall(r"\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)", path.read_text()):
            target = target.strip("<>")
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            destination = path.parent / unquote(parsed.path)
            if not destination.exists():
                errors.append(f"{path.relative_to(root)}: missing link target {target}")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Evidence valid: 30 PRH + 30 RH rows, {len(revisions)} commit objects, "
          f"RH regression names and {len(documents)} documents' local link targets")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
