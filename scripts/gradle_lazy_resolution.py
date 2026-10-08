#!/usr/bin/env python3
"""Bounded lexical Gradle guard, not a Kotlin/Groovy compiler or type resolver.

Only recognized execution bodies are deferred; configuration lambdas are not.
Comments/literal text cannot alter scopes. String interpolation remains code.
"""

import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Token:
    text: str
    offset: int
    line: int


class Lexer:
    def __init__(self, source: str, groovy: bool):
        self.source = source
        self.groovy = groovy
        self.position = 0
        self.tokens: list[Token] = []

    def emit(self, text: str, offset: int) -> None:
        self.tokens.append(Token(text, offset, self.source.count("\n", 0, offset) + 1))

    def string(self, delimiter: str, closing: str, interpolate: bool) -> None:
        start = self.position
        self.position += len(delimiter)
        self.emit("<string>", start)
        while self.position < len(self.source):
            i = self.position
            if self.source.startswith(closing, i):
                self.position += len(closing)
                return
            if self.source[i] == "\\" and delimiter not in ('"""', "$/"):
                self.position += 2
            elif delimiter == "$/" and self.source.startswith(("$$", "$/"), i):
                self.position += 2
            elif interpolate and self.source.startswith("${", i):
                # Parentheses isolate interpolated expressions from surrounding syntax.
                self.emit("(", i)
                self.position += 2
                self.code(interpolation=True)
                self.emit(")", self.position - 1)
            elif interpolate and self.groovy and self.source[i] == "$":
                match = re.match(r"\$([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)", self.source[i:])
                if match:
                    self.emit("(", i)
                    for part in re.finditer(r"[A-Za-z_]\w*|\.", match.group(1)):
                        self.emit(part.group(), i + 1 + part.start())
                    self.emit(")", i + len(match.group()))
                    self.position += len(match.group())
                else:
                    self.position += 1
            else:
                self.position += 1
        raise ValueError("unterminated string")

    def code(self, interpolation: bool = False) -> None:
        braces = 0
        while self.position < len(self.source):
            i = self.position
            char = self.source[i]
            if interpolation and char == "}" and braces == 0:
                self.position += 1
                return
            if self.source.startswith("//", i):
                end = self.source.find("\n", i)
                self.position = len(self.source) if end < 0 else end
            elif self.source.startswith("/*", i):
                depth = 1
                self.position += 2
                while depth and self.position < len(self.source):
                    if self.source.startswith("/*", self.position):
                        depth += 1
                        self.position += 2
                    elif self.source.startswith("*/", self.position):
                        depth -= 1
                        self.position += 2
                    else:
                        self.position += 1
                if depth:
                    raise ValueError("unterminated comment")
            elif self.source.startswith(('"""', "'''"), i):
                delimiter = self.source[i:i + 3]
                self.string(delimiter, delimiter, delimiter == '"""')
            elif char in ('"', "'"):
                self.string(char, char, char == '"')
            elif self.groovy and self.source.startswith("$/", i):
                self.string("$/", "/$", True)
            elif self.groovy and char == "/" and (
                next((token.text for token in reversed(self.tokens) if token.text != "\n"), None)
                in (None, "=", "(", "[", ",", ":", "return")
            ):
                self.string("/", "/", True)
            elif char == "\n":
                self.emit("\n", i)
                self.position += 1
            elif char.isspace():
                self.position += 1
            else:
                match = re.match(r"[A-Za-z_$][\w$]*", self.source[i:])
                text = match.group() if match else char
                self.emit(text, i)
                self.position += len(text)
                if char == "{":
                    braces += 1
                elif char == "}":
                    braces -= 1
        if interpolation:
            raise ValueError("unterminated interpolation")


def delimiter_pairs(tokens: list[Token]) -> dict[int, int]:
    stack = []
    pairs = {}
    for i, token in enumerate(tokens):
        if token.text in ("(", "[", "{"):
            stack.append(i)
        elif token.text in (")", "]", "}"):
            if not stack or tokens[stack[-1]].text != {")": "(", "]": "[", "}": "{"}[token.text]:
                raise ValueError("unbalanced delimiters")
            opening = stack.pop()
            pairs[opening] = i
            pairs[i] = opening
    if stack:
        raise ValueError("unbalanced delimiters")
    return pairs


def after_execution_expression(text: list[str], pairs: dict[int, int], call: int, body: int) -> int:
    """Find a known call's end, including only matched grouping parentheses.

    An enclosing helper call is not grouping: its returned value's consumer is
    not necessarily a consumer of the provider argument (no call-graph analysis).
    """
    start = call
    while start >= 2 and text[start - 1] == "." and re.fullmatch(r"[A-Za-z_$][\w$]*", text[start - 2]):
        start -= 2
    end = pairs[body]
    # Closure supplied as the sole parenthesized call argument, rather than a
    # trailing lambda. Do not skip a parenthesis belonging to any other scope.
    if body - 1 == call + 1 and text[body - 1] == "(" and pairs[body - 1] == end + 1:
        end += 1
    while start > 0 and text[start - 1] == "(" and pairs[start - 1] == end + 1:
        opening = start - 1
        before = text[opening - 1] if opening > 0 else ""
        if before in (")", "]") or (
            re.fullmatch(r"[A-Za-z_$][\w$]*", before) and before not in ("return", "throw")
        ):
            break
        start = opening
        end += 1
    return end + 1


def deferred_ranges(tokens: list[Token], pairs: dict[int, int]) -> list[tuple[int, int]]:
    text = [token.text for token in tokens]
    ranges = []
    for i, value in enumerate(text):
        if value == "{":
            previous = i - 1
            if previous >= 0 and text[previous] == ")":
                previous = pairs[previous] - 1
            elif previous >= 0 and text[previous] == "(":
                previous -= 1
            if previous >= 0 and text[previous] in ("doFirst", "doLast", "provider"):
                name = text[previous]
                is_provider = previous >= 2 and text[previous - 2:previous] == ["providers", "."]
                is_declaration = previous > 0 and text[previous - 1] in ("fun", "def", "void")
                after = after_execution_expression(text, pairs, previous, i)
                invoked = (after < len(text) and text[after] == "(") or text[after:after + 2] in (
                    [".", "call"], [".", "invoke"],
                )
                consumed_provider = name == "provider" and text[after:after + 2] in (
                    [".", "get"], [".", "getOrElse"], [".", "getOrNull"], [".", "orNull"],
                )
                if not is_declaration and not invoked and not consumed_provider and (name != "provider" or is_provider):
                    ranges.append((i, pairs[i]))

        if value != "(" or i == 0 or text[i - 1] in ("if", "while", "for"):
            continue
        # Method declarations only: never treat calls to asArguments as providers.
        name = i - 1
        start = name - 1
        while start >= 0 and text[start] not in ("{", "}", ";"):
            start -= 1
        header = text[start + 1:name]
        kotlin_method = "fun" in header and "=" not in header
        groovy_method = (
            bool(header) and header[-1] in (">", "void", "def", "Iterable", "List", "Collection", "String")
            and "=" not in header and "return" not in header
        )
        annotated = any(
            header[j] == "@" and "TaskAction" in header[j + 1:]
            for j in range(len(header))
        )
        if not (kotlin_method or groovy_method) or not (annotated or text[name] == "asArguments"):
            continue
        end = pairs[i] + 1
        while end < len(text) and text[end] not in ("{", "=", ";", "}"):
            end += 1
        if end == len(text) or text[end] in (";", "}"):
            continue
        if text[end] == "{":
            ranges.append((end, pairs[end]))
        elif kotlin_method:
            # Expression body: use source lines and balanced delimiters to find its end.
            body = end + 1
            end = body
            while end < len(text):
                if text[end] in ("}", ";"):
                    break
                if end > body:
                    if tokens[end].line > tokens[end - 1].line and text[end - 1] not in ("=", "+", ".", ",") and text[end] != ".":
                        break
                if text[end] in ("(", "[", "{"):
                    end = pairs[end]
                end += 1
            ranges.append((body - 1, end))
    return ranges


def findings(source: str, groovy: bool) -> list[int]:
    lexer = Lexer(source, groovy)
    lexer.code()
    # Preserve line breaks for expression bodies while simplifying structural navigation.
    tokens = [token for token in lexer.tokens if token.text != "\n"]
    pairs = delimiter_pairs(tokens)
    deferred = deferred_ranges(tokens, pairs)
    text = [token.text for token in tokens]
    aliases = {"configurations"}
    for i, value in enumerate(text):
        if value in ("val", "var", "def") and i + 2 < len(text):
            end = i + 2
            while end < len(text) and text[end] not in (";", "}") and tokens[end].line == tokens[i].line:
                end += 1
            if "=" in text[i + 2:end] and aliases.intersection(text[i + 2:end]):
                aliases.add(text[i + 1])
    result = []
    for i, value in enumerate(text):
        if i == 0 or text[i - 1] != ".":
            continue
        resolution = value in ("asPath", "singleFile")
        if value in ("files", "getFiles"):
            # Walk the receiver chain, not the surrounding statement. inputs.files(cp)
            # is registration; configurations.getByName(...).files is consumption.
            start = i - 2
            while start >= 0:
                if text[start] in (")", "]"):
                    start = pairs[start] - 1
                elif start >= 2 and text[start - 1] == ".":
                    start -= 2
                else:
                    break
            resolution = start >= 0 and text[start] in aliases
        if resolution and not any(start < i < end for start, end in deferred):
            result.append(tokens[i].line)
    return sorted(set(result))


def source_inventory() -> tuple[list[Path], int]:
    """Select real CWD source, pruning state before traversing its directories.

    Filename inclusion globs can override traversal/ignore behavior, so enumerate
    ordinary files first and select script suffixes afterward. A verified checkout
    index also supplies tracked source that local ignore rules might hide.
    """
    tracked = b""
    indexed_links = set()
    git_environment = None
    if Path(".git").exists():
        git_environment, status = default_git_environment()
        if status:
            return [], status
        checkout = subprocess.run(
            ["git", "--no-optional-locks", "rev-parse", "--show-toplevel"],
            stdout=subprocess.PIPE, env=git_environment, check=False,
        )
        if checkout.returncode:
            return [], checkout.returncode
        if Path(checkout.stdout.decode().removesuffix("\n")).resolve() != Path.cwd().resolve():
            print("Gradle source inventory failed: Git context is not the caller's checkout", file=sys.stderr)
            return [], 2
        indexed = subprocess.run(
            ["git", "--no-optional-locks", "ls-files", "--cached", "--stage", "-z"],
            stdout=subprocess.PIPE, env=git_environment, check=False,
        )
        if indexed.returncode:
            return [], indexed.returncode
        records = [record.split(b"\t", 1) for record in indexed.stdout.split(b"\0") if record]
        tracked = b"".join(filename + b"\0" for _, filename in records)
        indexed_links = {Path(filename.decode()) for metadata, filename in records if metadata.split()[0] == b"120000"}
    discovered = subprocess.run(
        ["rg", "--no-config", "--files", "-0", "--hidden", "--no-ignore-dot",
         "--no-ignore-global", "--no-ignore-parent", "--glob", "!**/.git/**",
         "--glob", "!**/.gradle/**", "--glob", "!**/build/**", "--glob", "!**/.worktrees/**", "."],
        stdout=subprocess.PIPE, check=False,
    )
    if discovered.returncode not in (0, 1):
        return [], discovered.returncode
    paths = {
        Path(filename.decode()) for filename in (tracked + discovered.stdout).split(b"\0")
        if filename and filename.endswith((b".gradle", b".gradle.kts"))
    }
    # VCS/cache/worktree state is never source, even if accidentally indexed.
    paths = {path for path in paths if not {".git", ".gradle", ".worktrees"}.intersection(path.parts)}
    covered, status = alias_source_inventory(paths | indexed_links, git_environment)
    if status:
        return [], status
    paths |= covered
    return sorted(paths), 0


def default_git_environment() -> tuple[dict[str, str] | None, int]:
    """Only Git inventory children lose Git-reported repository-local context."""
    import os

    names = subprocess.run(
        ["git", "--no-optional-locks", "rev-parse", "--local-env-vars"],
        stdout=subprocess.PIPE, check=False,
    )
    if names.returncode:
        return None, names.returncode
    environment = os.environ.copy()
    for name in names.stdout.decode().splitlines():
        environment.pop(name, None)
    return environment, 0


def resolve_source_path(path: Path) -> tuple[Path, bool]:
    """Resolve aliases componentwise without accessing external/private targets."""
    import os
    import stat
    from collections import deque

    root = Path.cwd().resolve()
    requested = path
    links = set()
    pending_directory = False

    def anchored_parts(raw: str) -> list[str]:
        nonlocal pending_directory
        if raw.endswith("/"):
            pending_directory = True
        parts = [part for part in raw.split("/") if part]
        if raw.startswith("/"):
            prefix = list(root.parts[1:])
            if parts[:len(prefix)] != prefix:
                raise ValueError("alias leaves checkout")
            parts = parts[len(prefix):]
        return parts

    try:
        pending = deque(anchored_parts(os.fspath(path)))
        cursor = root
        while pending:
            part = pending.popleft()
            if part == ".":
                continue
            if part == "..":
                if cursor == root:
                    raise ValueError("alias leaves checkout")
                cursor = cursor.parent
                continue
            if links and part in {".git", ".gradle", ".worktrees", "build"}:
                raise ValueError("alias crosses generated/private boundary")
            candidate = cursor / part
            metadata = os.lstat(candidate)
            if stat.S_ISLNK(metadata.st_mode):
                if not links:
                    consumed = cursor.relative_to(root).parts
                    if any(part in {".git", ".gradle", ".worktrees", "build"} for part in consumed):
                        raise ValueError(f"Unsupported source symlink: {requested}: alias crosses generated/private boundary")
                identity = (metadata.st_dev, metadata.st_ino)
                if identity in links or len(links) >= 40:
                    raise ValueError("alias cycle or 40-link resolution bound")
                links.add(identity)
                target = os.readlink(candidate)
                target_parts = anchored_parts(target)
                if target.startswith("/"):
                    cursor = root
                # Expand at the reached link before consuming any later '..'.
                pending.extendleft(reversed(target_parts))
                continue
            cursor = candidate
            if (pending or pending_directory) and not stat.S_ISDIR(metadata.st_mode):
                raise NotADirectoryError(20, "Not a directory", str(cursor))
            if links:
                if not (stat.S_ISREG(metadata.st_mode) or stat.S_ISDIR(metadata.st_mode)):
                    raise ValueError("unsupported target type")
                required = os.R_OK | (os.X_OK if stat.S_ISDIR(metadata.st_mode) else 0)
                if not os.access(cursor, required, effective_ids=True):
                    raise PermissionError(13, "Permission denied", str(cursor))
        return cursor.relative_to(root), bool(links)
    except (OSError, ValueError) as error:
        if links:
            raise ValueError(f"Unsupported source symlink: {requested}: {error}") from error
        raise


def alias_source_inventory(indexed_paths: set[Path], git_environment: dict[str, str] | None) -> tuple[set[Path], int]:
    """Visit bounded physical directories; aliases force real target source coverage."""
    import os
    import stat

    private = {".git", ".gradle", ".worktrees"}
    covered = set()
    pending = [(Path("."), False)]
    visited = set()
    edges = {}
    names = {}
    try:
        for path in sorted(indexed_paths):
            if private.intersection(path.parts):
                continue
            physical, aliased = resolve_source_path(path)
            if aliased and physical.is_dir():
                pending.append((physical, True))
            elif path.name.endswith((".gradle", ".gradle.kts")):
                covered.add(path)
        while pending:
            directory, forced = pending.pop()
            metadata = os.stat(directory, follow_symlinks=False)
            identity = (metadata.st_dev, metadata.st_ino)
            key = (identity, forced)
            if key in visited or (not forced and (identity, True) in visited):
                continue
            visited.add(key)
            names[identity] = directory
            edges.setdefault(identity, set())
            with os.scandir(directory) as entries:
                candidates = [(Path(entry.path), entry.is_symlink())
                              for entry in entries if entry.name not in private and entry.name != "build"]
            ignored = set()
            if git_environment is not None and candidates and not forced:
                selection = subprocess.run(
                    ["git", "--no-optional-locks", "check-ignore", "--no-index", "-z", "--stdin"],
                    input=b"".join(os.fsencode(path) + b"\0" for path, _ in candidates),
                    stdout=subprocess.PIPE, env=git_environment, check=False,
                )
                if selection.returncode not in (0, 1):
                    return set(), selection.returncode
                ignored = {Path(os.fsdecode(path)) for path in selection.stdout.split(b"\0") if path}
            for path, linked in candidates:
                if path in ignored:
                    continue
                physical, aliased = resolve_source_path(path) if linked else (path, False)
                target_metadata = os.stat(physical, follow_symlinks=False)
                if stat.S_ISDIR(target_metadata.st_mode):
                    child = (target_metadata.st_dev, target_metadata.st_ino)
                    edges[identity].add(child)
                    pending.append((physical, forced or aliased))
                elif path.name.endswith((".gradle", ".gradle.kts")):
                    covered.add(path)
        colors = {}
        for start in edges:
            if colors.get(start):
                continue
            colors[start] = 1
            stack = [(start, iter(edges[start]))]
            while stack:
                node, children = stack[-1]
                child = next(children, None)
                if child is None:
                    colors[node] = 2
                    stack.pop()
                elif colors.get(child) == 1:
                    raise ValueError(f"Unsupported source symlink: directory cycle at {names.get(child, child)}")
                elif not colors.get(child):
                    colors[child] = 1
                    stack.append((child, iter(edges.get(child, ()))))
    except (OSError, ValueError) as error:
        print(f"Gradle source inventory failed: {error}", file=sys.stderr)
        return set(), 2
    return covered, 0


def main() -> int:
    paths, status = source_inventory()
    if status:
        return status
    bad = False
    for path in paths:
        try:
            physical, _ = resolve_source_path(path)
            source = physical.read_text()
        except (OSError, ValueError) as error:
            print(f"Gradle source read failed: {path}: {error}", file=sys.stderr)
            bad = True
            continue
        try:
            lines = findings(source, path.suffix == ".gradle")
        except ValueError as error:
            print(f"Gradle resolution scan failed: {path}: {error}", file=sys.stderr)
            bad = True
            continue
        for line in lines:
            print(f"Eager Gradle configuration resolution: {path}:{line}: {source.splitlines()[line - 1].strip()}", file=sys.stderr)
            bad = True
    return int(bad)


if __name__ == "__main__":
    sys.exit(main())
