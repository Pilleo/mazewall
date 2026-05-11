#!/usr/bin/env python3
"""
Add full-width dashed enforcement lines at the kernel boundary.
One per defense layer, stacked vertically, each a distinct color.
Lines accumulate progressively across slides.
"""

with open('presentation/presentation.html', 'r') as f:
    html = f.read()

# ── 1. INSERT four enforcement lines just before the syscall boxes ──────────
ENFORCEMENT_LINES = '''
  <!-- ═══════════════════════════════════════════════════════════════════ -->
  <!-- ENFORCEMENT LINES: full-width dashed, one per defense layer.       -->
  <!-- They stack in the zone between the boundary and the syscall boxes. -->
  <!-- Each new slide adds a line → viewer sees "another layer appeared". -->
  <!-- ═══════════════════════════════════════════════════════════════════ -->

  <!-- LINE 1: BoB Whitelist (Slide 2+) — teal -->
  <g id="lineWhitelist" class="slide-layer interactive">
    <title>BoB Whitelist enforcement line — every syscall must be explicitly declared in the behavioral profile</title>
    <line x1="50" y1="558" x2="1050" y2="558"
          stroke="var(--accent)" stroke-width="3" stroke-dasharray="12,5" opacity="0.85"
          filter="url(#glow-teal)"/>
    <text x="55" y="554" fill="var(--accent)" font-size="8" font-weight="bold" opacity="0.9">BoB Whitelist</text>
  </g>

  <!-- LINE 2: Lifecycle Phase Lock (Slide 3+) — grey -->
  <g id="linePhase" class="slide-layer interactive">
    <title>Lifecycle Phase enforcement line — syscalls permitted only in the appropriate lifecycle phase (Startup / Runtime / Shutdown)</title>
    <line x1="50" y1="567" x2="1050" y2="567"
          stroke="#a0aec0" stroke-width="3" stroke-dasharray="12,5" opacity="0.85"/>
    <text x="55" y="563" fill="#a0aec0" font-size="8" font-weight="bold" opacity="0.9">Phase Lock</text>
  </g>

  <!-- LINE 3: Stacktrace / Call-Path (Slide 4+) — green -->
  <g id="lineStack" class="slide-layer interactive">
    <title>Call-path enforcement line — eBPF unwinder verifies frame pointers at syscall entry match the declared call-stack profile (native apps only)</title>
    <line x1="50" y1="549" x2="1050" y2="549"
          stroke="var(--success-color)" stroke-width="3" stroke-dasharray="12,5" opacity="0.8"
          filter="url(#glow-neon)"/>
    <text x="55" y="545" fill="var(--success-color)" font-size="8" font-weight="bold" opacity="0.9">Stack Gate</text>
  </g>

  <!-- LINE 4: Thread Scope / Context (Slide 5+) — orange -->
  <g id="lineScope" class="slide-layer interactive">
    <title>Thread-scope enforcement line — eBPF LSM hook checks dynamic thread context (role, tenant, path bounds) at syscall entry</title>
    <line x1="50" y1="540" x2="1050" y2="540"
          stroke="var(--warning-color)" stroke-width="3" stroke-dasharray="12,5" opacity="0.8"
          filter="url(#glow-orange)"/>
    <text x="55" y="536" fill="var(--warning-color)" font-size="8" font-weight="bold" opacity="0.9">Scope Gate</text>
  </g>

'''

# Insert before the Syscall Boxes comment
html = html.replace('  <!-- Syscall Boxes -->', ENFORCEMENT_LINES + '  <!-- Syscall Boxes -->')

# ── 2. ADD lines to JS allLayers list ──────────────────────────────────────
html = html.replace(
    "'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'badgeReadScope',",
    "'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'badgeReadScope',\n    'lineWhitelist', 'linePhase', 'lineStack', 'lineScope',"
)

# ── 3. ADD lines to each slide's show array ─────────────────────────────────
# Slide 2: +lineWhitelist
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'badgeExecveWhitelist', 'redAttackSlide2', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'badgeExecveWhitelist', 'lineWhitelist', 'redAttackSlide2', 'syscalls', 'legend']"
)
# Slide 3: +lineWhitelist, +linePhase
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'startupBulkhead', 'shutdownGate', 'redAttackSlide3', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'lineWhitelist', 'linePhase', 'startupBulkhead', 'shutdownGate', 'redAttackSlide3', 'syscalls', 'legend']"
)
# Slide 4: +lineWhitelist, +linePhase, +lineStack
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'startupBulkhead', 'shutdownGate', 'stacktraceEnforcement', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'lineWhitelist', 'linePhase', 'lineStack', 'startupBulkhead', 'shutdownGate', 'stacktraceEnforcement', 'syscalls', 'legend']"
)
# Slide 5: +lineWhitelist, +linePhase, +lineStack, +lineScope
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadScope', 'startupBulkhead', 'shutdownGate', 'threadScope', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadScope', 'lineWhitelist', 'linePhase', 'lineStack', 'lineScope', 'startupBulkhead', 'shutdownGate', 'threadScope', 'syscalls', 'legend']"
)

# ── 4. UPDATE legend to include all 4 lines ──────────────────────────────────
OLD_LEGEND = '''    <g class="interactive">
      <title>Application behavior profile (Bill of Behavior) enclosing allowed execution boundaries</title>
      <line x1="780" y1="758" x2="810" y2="758" stroke="var(--accent)" stroke-width="3"/>
      <text x="816" y="762" fill="var(--text-secondary)" font-size="8">BoB</text>
    </g>'''

NEW_LEGEND = '''    <g class="interactive">
      <title>BoB Whitelist: teal dashed line = syscall allowed list enforced at kernel boundary</title>
      <line x1="780" y1="756" x2="810" y2="756" stroke="var(--accent)" stroke-width="2.5" stroke-dasharray="6,3"/>
      <text x="816" y="760" fill="var(--text-secondary)" font-size="8">Whitelist</text>
    </g>
    <g class="interactive">
      <title>Phase Lock: grey dashed line = lifecycle-partitioned enforcement (mprotect dropped after readiness probe)</title>
      <line x1="780" y1="768" x2="810" y2="768" stroke="#a0aec0" stroke-width="2.5" stroke-dasharray="6,3"/>
      <text x="816" y="772" fill="var(--text-secondary)" font-size="8">Phase</text>
    </g>'''

html = html.replace(OLD_LEGEND, NEW_LEGEND)

with open('presentation/presentation.html', 'w') as f:
    f.write(html)

print("Done — enforcement lines added.")
