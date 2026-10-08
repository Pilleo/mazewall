#!/usr/bin/env python3
"""Actual finished executable measurement ownership negatives; private fixtures only."""
import argparse
from pathlib import Path
import shutil
import tempfile
from fixture_io import run_child


def check(jar, java, evidence):
    evidence.mkdir(parents=True, exist_ok=False)
    failures = []
    for mode, names in [("measure", ["candidate.json", "candidate-generation.txt"]),
                        ("measure-unsupported", ["unsupported-candidate.json", "unsupported-generation.txt"])]:
        for name in names:
            for kind in ["existing", "symlink", "hardlink"]:
                root = Path(tempfile.mkdtemp(prefix="task13-measure-ownership-"))
                shutil.copyfile(jar, root / "application.jar")
                (root / "template.txt").write_text("Reviewed quarterly template")
                (root / "reports").mkdir()
                (root / "reports/quarterly.txt").write_text("")
                target = root / "operator-original.txt"
                target.write_text("operator owned: must survive")
                output = root / name
                if kind == "existing":
                    output.write_bytes(target.read_bytes())
                elif kind == "symlink":
                    output.symlink_to(target)
                else:
                    output.hardlink_to(target)
                before = output.lstat()
                command = [java, "--enable-native-access=ALL-UNNAMED", "-jar", "application.jar", mode, str(root)]
                tag = f"{mode}-{name}-{kind}"
                result = run_child(command, root, "measurement-check", timeout=30)
                (evidence / (tag + ".log")).write_text(result.stdout + result.stderr)
                shutil.copyfile(root / "measurement-check.command.json", evidence / (tag + ".json"))
                if result.returncode == 0 or target.read_text() != "operator owned: must survive" or output.read_text() != "operator owned: must survive" or output.lstat().st_ino != before.st_ino:
                    failures.append(tag)
                other = names[1] if name == names[0] else names[0]
                if (root / other).exists():
                    failures.append(tag + ": unexpected partial pair")
        root = Path(tempfile.mkdtemp(prefix="task13-measure-directory-alias-"))
        shutil.copyfile(jar, root / "application.jar")
        alias = root / "deployment-alias"
        alias.symlink_to(root, target_is_directory=True)
        command = [java, "--enable-native-access=ALL-UNNAMED", "-jar", "application.jar", mode, str(alias)]
        result = run_child(command, root, "directory-alias-check", timeout=30)
        (evidence / (mode + "-directory-alias.log")).write_text(result.stdout + result.stderr)
        shutil.copyfile(root / "directory-alias-check.command.json", evidence / (mode + "-directory-alias.json"))
        if result.returncode == 0 or "symlink/non-directory alias" not in result.stderr or any((root / name).exists() for name in names):
            failures.append(mode + ": directory alias accepted")
    assert not failures, "Measurement damaged/accepted unknown artifacts: " + ", ".join(failures)
    print("MEASUREMENT_OWNERSHIP_PASS 14 existing/symlink/hardlink/directory-alias cases", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    check(args.jar.resolve(), args.java, args.evidence.resolve())
