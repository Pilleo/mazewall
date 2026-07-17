---
title: "SbobUnicodeTest fails on systems with ASCII/non-UTF-8 JNU encoding"
status: "resolved"
priority: 3
severity: "MEDIUM"
scope: "enforcer"
dependencies: []
target_files:
  - "io.mazewall.SbobUnicodeTest"
github_issue: 114
---

# Description
The unit test `SbobUnicodeTest` (`should parse unicode escape sequences in paths`) fails on environments where the system property `sun.jnu.encoding` is set to `ANSI_X3.4-1968` (US-ASCII) or any other encoding that does not support non-ASCII characters. 

The test tries to parse an SBoB JSON specifying a path with a Unicode escape character (e.g. `/opt/caf\u00e9` which resolves to `/opt/café`). Inside `SbobParser`, `PathNormalizer.normalizeAndPrune` calls `java.nio.file.Paths.get(pathStr)` on the path string. On systems with an ASCII JNU encoding, `Paths.get` throws `java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters`.

# Impact
Builds fail on machines or containers configured with default ASCII locales (such as minimal CI runner environments).

# Proposed Solution
Skip the test dynamically using JUnit 5 `Assumptions.assumeTrue` if the default JVM file system/JNU encoding cannot represent/parse the target path `/opt/café`.
