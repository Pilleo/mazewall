#!/usr/bin/env python3
import re

with open('presentation/presentation.html', 'r') as f:
    html = f.read()

# Remove the old enforcement lines that were added previously
html = re.sub(r'<!-- ═══════════════════════════════════════════════════════════════════ -->.*?<!-- Syscall Boxes -->', '<!-- Syscall Boxes -->', html, flags=re.DOTALL)

NEW_LINES = '''
  <!-- ═══════════════════════════════════════════════════════════════════ -->
  <!-- ENFORCEMENT LINES (Inside Kernel Space)                             -->
  <!-- ═══════════════════════════════════════════════════════════════════ -->

  <!-- LINE 1: BoB Whitelist -->
  <g id="lineWhitelist_active" class="slide-layer interactive">
    <line x1="50" y1="570" x2="1050" y2="570" stroke="var(--accent)" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-teal)"/>
    <text x="65" y="566" fill="var(--accent)" font-size="9" font-weight="bold">BoB Whitelist</text>
  </g>
  <g id="lineWhitelist_dim" class="slide-layer">
    <line x1="50" y1="570" x2="1050" y2="570" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="566" fill="#4a5568" font-size="8" opacity="0.6">BoB Whitelist</text>
  </g>

  <!-- LINE 2: Lifecycle Phase Lock -->
  <g id="linePhase_active" class="slide-layer interactive">
    <line x1="50" y1="582" x2="1050" y2="582" stroke="#e2e8f0" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-blue)"/>
    <text x="65" y="578" fill="#e2e8f0" font-size="9" font-weight="bold">Phase Lock</text>
  </g>
  <g id="linePhase_dim" class="slide-layer">
    <line x1="50" y1="582" x2="1050" y2="582" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="578" fill="#4a5568" font-size="8" opacity="0.6">Phase Lock</text>
  </g>

  <!-- LINE 3: Stacktrace -->
  <g id="lineStack_active" class="slide-layer interactive">
    <line x1="50" y1="594" x2="1050" y2="594" stroke="var(--success-color)" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-neon)"/>
    <text x="65" y="590" fill="var(--success-color)" font-size="9" font-weight="bold">Stack Gate</text>
  </g>
  <g id="lineStack_dim" class="slide-layer">
    <line x1="50" y1="594" x2="1050" y2="594" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="590" fill="#4a5568" font-size="8" opacity="0.6">Stack Gate</text>
  </g>

  <!-- LINE 4: Thread Scope -->
  <g id="lineScope_active" class="slide-layer interactive">
    <line x1="50" y1="606" x2="1050" y2="606" stroke="var(--warning-color)" stroke-width="3" stroke-dasharray="12,5" filter="url(#glow-orange)"/>
    <text x="65" y="602" fill="var(--warning-color)" font-size="9" font-weight="bold">Scope Gate</text>
  </g>
  <g id="lineScope_dim" class="slide-layer">
    <line x1="50" y1="606" x2="1050" y2="606" stroke="#4a5568" stroke-width="2" stroke-dasharray="12,5" opacity="0.4"/>
    <text x="65" y="602" fill="#4a5568" font-size="8" opacity="0.6">Scope Gate</text>
  </g>

'''

html = html.replace('  <!-- Syscall Boxes -->', NEW_LINES + '  <!-- Syscall Boxes -->')

# Ensure the badges are drawn AFTER the lines so they sit on top.
# In the SVG, the badges are currently before the slides.
# We will move the NEW_LINES to be right AFTER "KERNEL-SPACE" text and boundary.
# Wait, let's just make sure badges are lower in the file than the lines.
# Right now, BADGES are at line 460, NEW_LINES are at line 680 (before syscall boxes).
# So NEW_LINES are drawn AFTER the badges! That means lines will cover the badges!
# We must move the BADGES to be AFTER the NEW_LINES.
# Let's extract badges and put them before syscall boxes, after NEW_LINES.

badges_match = re.search(r'(<!-- ╔══════════════════════════════════════════╗ -->.*?<!-- ╚══════════════════════════════════════════╝ -->.*?</g>\s*<!-- BADGE: read – scope denied \(Slide 5\) -->.*?</g>\s*)', html, flags=re.DOTALL)
if badges_match:
    badges_block = badges_match.group(1)
    html = html.replace(badges_block, '')
    html = html.replace('  <!-- Syscall Boxes -->', badges_block + '\n  <!-- Syscall Boxes -->')

# Update JS allLayers
html = html.replace(
    "'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'badgeReadScope',\n    'lineWhitelist', 'linePhase', 'lineStack', 'lineScope',",
    "'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'badgeReadScope',\n    'lineWhitelist_active', 'lineWhitelist_dim', 'linePhase_active', 'linePhase_dim', 'lineStack_active', 'lineStack_dim', 'lineScope_active', 'lineScope_dim',"
)
html = html.replace(
    "'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'badgeReadScope',\n    'redAttackSlide2'",
    "'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'badgeReadScope',\n    'lineWhitelist_active', 'lineWhitelist_dim', 'linePhase_active', 'linePhase_dim', 'lineStack_active', 'lineStack_dim', 'lineScope_active', 'lineScope_dim',\n    'redAttackSlide2'"
)

# Update show arrays
# Slide 2: lineWhitelist_active
html = re.sub(
    r"show: \['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'badgeExecveWhitelist', (?:'lineWhitelist', )?'redAttackSlide2', 'syscalls', 'legend'\]",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'lineWhitelist_active', 'badgeExecveWhitelist', 'redAttackSlide2', 'syscalls', 'legend']",
    html
)

# Slide 3: lineWhitelist_dim, linePhase_active
html = re.sub(
    r"show: \['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'badgeExecveWhitelist', 'badgeMprotectPhase', (?:'lineWhitelist', 'linePhase', )?'startupBulkhead', 'shutdownGate', 'redAttackSlide3', 'syscalls', 'legend'\]",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'lineWhitelist_dim', 'linePhase_active', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'startupBulkhead', 'shutdownGate', 'redAttackSlide3', 'syscalls', 'legend']",
    html
)

# Slide 4: lineWhitelist_dim, linePhase_dim, lineStack_active
html = re.sub(
    r"show: \['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', (?:'lineWhitelist', 'linePhase', 'lineStack', )?'startupBulkhead', 'shutdownGate', 'stacktraceEnforcement', 'syscalls', 'legend'\]",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'lineWhitelist_dim', 'linePhase_dim', 'lineStack_active', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'startupBulkhead', 'shutdownGate', 'stacktraceEnforcement', 'syscalls', 'legend']",
    html
)

# Slide 5: lineWhitelist_dim, linePhase_dim, lineStack_dim, lineScope_active
html = re.sub(
    r"show: \['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadScope', (?:'lineWhitelist', 'linePhase', 'lineStack', 'lineScope', )?'startupBulkhead', 'shutdownGate', 'threadScope', 'syscalls', 'legend'\]",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'lineWhitelist_dim', 'linePhase_dim', 'lineStack_dim', 'lineScope_active', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadScope', 'startupBulkhead', 'shutdownGate', 'threadScope', 'syscalls', 'legend']",
    html
)

# Remove the text label from the kernel boundary to avoid clutter
html = html.replace('<text x="430" y="552" fill="#ffffff" font-size="9" opacity="0.4">—— user / kernel boundary ——</text>', '')

with open('presentation/presentation.html', 'w') as f:
    f.write(html)

print("Done — dimming logic and vertical repositioning added.")
