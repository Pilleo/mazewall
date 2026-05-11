#!/usr/bin/env python3
"""
Replace the single shared BoB gate bar with per-syscall enforcement badges.
Each check gets its own colored badge above the specific syscall it blocks.
"""
import re

with open('presentation/presentation.html', 'r') as f:
    html = f.read()

# ── 1. REMOVE shared bobWhitelist bar, execveDenied, bobPhaseGuard ─────────
html = re.sub(r'\s*<!-- Slide 2: BoB Whitelist Filter Bank -->.*?</g>\s*\n', '\n', html, flags=re.DOTALL)
html = re.sub(r'\s*<!-- execve DENIED state -->.*?</g>\s*\n', '\n', html, flags=re.DOTALL)
html = re.sub(r'\s*<!-- Slide 3: Phase Guard.*?</g>\s*\n', '\n', html, flags=re.DOTALL)

# ── 2. INSERT per-syscall badges before SLIDE 2 attack ─────────────────────
BADGES = '''
  <!-- ╔══════════════════════════════════════════╗ -->
  <!-- ║  PER-SYSCALL ENFORCEMENT BADGES          ║ -->
  <!-- ║  Each badge sits above its syscall box.  ║ -->
  <!-- ║  Slides accumulate badges progressively. ║ -->
  <!-- ╚══════════════════════════════════════════╝ -->

  <!-- BADGE: execve – not in whitelist (Slide 2+) -->
  <g id="badgeExecveWhitelist" class="slide-layer interactive">
    <title>execve() is NOT in the BoB whitelist — blocked at the kernel boundary</title>
    <rect x="750" y="562" width="122" height="44" rx="4"
          fill="var(--accent)" opacity="0.92" filter="url(#glow-teal)"/>
    <text x="811" y="578" fill="#0d1117" font-size="9" font-weight="bold" text-anchor="middle">✗ NOT WHITELISTED</text>
    <text x="811" y="591" fill="#0d1117" font-size="7" text-anchor="middle">BoB: execve ∉ profile</text>
    <text x="811" y="602" fill="#0d1117" font-size="7" text-anchor="middle">eBPF → SIGKILL</text>
  </g>

  <!-- BADGE: mprotect – phase-locked (Slide 3+) -->
  <g id="badgeMprotectPhase" class="slide-layer interactive">
    <title>mprotect(PROT_EXEC) dropped permanently after the k8s readiness probe — phase-locked</title>
    <rect x="900" y="562" width="122" height="44" rx="4"
          fill="#718096" opacity="0.95"/>
    <text x="961" y="578" fill="#ffffff" font-size="9" font-weight="bold" text-anchor="middle">⊘ PHASE-LOCKED</text>
    <text x="961" y="591" fill="#e2e8f0" font-size="7" text-anchor="middle">dropped after readiness probe</text>
    <text x="961" y="602" fill="#e2e8f0" font-size="7" text-anchor="middle">eBPF lifecycle partition</text>
  </g>

  <!-- BADGE: read – stack mismatch (Slide 4) -->
  <g id="badgeReadStack" class="slide-layer interactive">
    <title>read() call-path mismatch: actual stack contains ROP chain, not the expected Controller→Service→FileUtil path</title>
    <rect x="355" y="562" width="110" height="44" rx="4"
          fill="var(--success-color)" opacity="0.88" filter="url(#glow-neon)"/>
    <text x="410" y="578" fill="#0d1117" font-size="9" font-weight="bold" text-anchor="middle">✗ STACK MISMATCH</text>
    <text x="410" y="591" fill="#0d1117" font-size="7" text-anchor="middle">ROP chain in frame ptrs</text>
    <text x="410" y="602" fill="#0d1117" font-size="7" text-anchor="middle">native apps only</text>
  </g>

  <!-- BADGE: read – scope denied (Slide 5) -->
  <g id="badgeReadScope" class="slide-layer interactive">
    <title>read() scope check: path /etc/passwd is outside the permitted /public/ boundary for this thread's role</title>
    <rect x="340" y="562" width="140" height="44" rx="4"
          fill="var(--warning-color)" opacity="0.95" filter="url(#glow-orange)"/>
    <text x="410" y="578" fill="#0d1117" font-size="9" font-weight="bold" text-anchor="middle">⊘ SCOPE DENIED</text>
    <text x="410" y="591" fill="#0d1117" font-size="7" text-anchor="middle">/etc/passwd ∉ /public/</text>
    <text x="410" y="602" fill="#0d1117" font-size="7" text-anchor="middle">eBPF LSM thread-ctx map</text>
  </g>

'''

html = html.replace('  <!-- ============ SLIDE 2: Hardened Walls ============ -->', BADGES + '  <!-- ============ SLIDE 2: ============ -->')

# ── 3. FIX Slide 2 attack – ends at top of execve badge (y=562) ────────────
html = html.replace(
    'points="550,145 600,145 600,200 680,200 680,260 760,260 760,340 810,340 810,580"\n      fill="none" stroke="var(--attack-color)" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" opacity="0.7"',
    'points="550,145 600,145 600,200 680,200 680,260 760,260 760,340 810,340 810,562"\n      fill="none" stroke="var(--attack-color)" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" opacity="0.7"'
)
# Remove old slide2 denied box/arrows that crashed into the bar
html = re.sub(r'<rect x="785" y="585".*?</g>\s*\n', '', html, flags=re.DOTALL)

# ── 4. FIX Slide 3 attack – ends at top of mprotect badge (y=562) ──────────
html = html.replace(
    'points="550,145 600,145 600,240 680,240 680,320 760,320 760,380 840,380 840,460 950,460 950,580"',
    'points="550,145 600,145 600,240 680,240 680,320 760,320 760,380 840,380 840,460 961,460 961,562"'
)
# Fix crash arrows for slide 3
html = html.replace(
    '<line x1="950" y1="580" x2="935" y2="562" stroke="#ff6b6b" stroke-width="3" class="pulse-block"/>',
    '<line x1="961" y1="562" x2="946" y2="544" stroke="#ff6b6b" stroke-width="3" class="pulse-block"/>'
)
html = html.replace(
    '<line x1="950" y1="580" x2="965" y2="562" stroke="#ff6b6b" stroke-width="3" class="pulse-block"/>',
    '<line x1="961" y1="562" x2="976" y2="544" stroke="#ff6b6b" stroke-width="3" class="pulse-block"/>'
)
# Fix slide 3 denial label position
html = html.replace(
    '<rect x="870" y="595" width="185" height="22" rx="3" fill="#0f2038" stroke="var(--attack-color)" stroke-width="1.5"/>',
    '<rect x="870" y="540" width="185" height="22" rx="3" fill="#0f2038" stroke="var(--attack-color)" stroke-width="1.5"/>'
)
html = html.replace(
    '<text x="960" y="610" fill="var(--attack-color)" font-size="9" font-weight="bold" text-anchor="middle">mprotect() ⊘ PHASE-DENIED</text>',
    '<text x="960" y="555" fill="var(--attack-color)" font-size="9" font-weight="bold" text-anchor="middle">mprotect() ⊘ PHASE-DENIED</text>'
)

# ── 5. FIX Slide 4 attack – ends at top of read badge (y=562) ──────────────
html = html.replace(
    'points="550,145 600,180 650,220 700,260 750,300 780,340 780,380 750,420 700,450 650,475 600,520 550,555 490,590 410,610"',
    'points="550,145 600,180 650,220 700,260 750,300 780,340 780,380 750,420 700,450 650,475 600,510 550,540 480,560 410,562"'
)
# Fix slide 4 crash box/arrows
html = html.replace(
    '<rect x="350" y="600" width="120" height="50" rx="3" fill="none" stroke="var(--attack-color)" stroke-width="2.5" stroke-dasharray="3,2" opacity="0.9" class="pulse-block"/>',
    ''
)
html = html.replace(
    '<line x1="410" y1="600" x2="395" y2="582" stroke="#ff6b6b" stroke-width="3"/>',
    '<line x1="410" y1="562" x2="395" y2="544" stroke="#ff6b6b" stroke-width="3" class="pulse-block"/>'
)
html = html.replace(
    '<line x1="410" y1="600" x2="425" y2="582" stroke="#ff6b6b" stroke-width="3"/>',
    '<line x1="410" y1="562" x2="425" y2="544" stroke="#ff6b6b" stroke-width="3" class="pulse-block"/>'
)
html = html.replace(
    '<text x="445" y="597" fill="var(--attack-color)" font-size="10" font-weight="bold">DENIED ✕</text>',
    '<text x="450" y="542" fill="var(--attack-color)" font-size="10" font-weight="bold">DENIED ✕</text>'
)

# ── 6. FIX Slide 5 trojan thread – ends at scope badge top (y=562) ─────────
# Trojan thread ends at 380,555 → change to 380,562
html = html.replace(
    'points="550,145 550,180 510,180 510,240 470,240 470,300 430,300 430,360 400,360 400,420 380,420 380,555"\n        fill="none" stroke="var(--thread-color)"',
    'points="550,145 550,180 510,180 510,240 470,240 470,300 430,300 430,360 400,360 400,420 380,420 380,562"\n        fill="none" stroke="var(--thread-color)"'
)
html = html.replace(
    'points="550,145 550,180 510,180 510,240 470,240 470,300 430,300 430,360 400,360 400,420 380,420 380,555"\n        fill="none" stroke="var(--attack-color)"',
    'points="550,145 550,180 510,180 510,240 470,240 470,300 430,300 430,360 400,360 400,420 380,420 380,562"\n        fill="none" stroke="var(--attack-color)"'
)
# Fix slide 5 scope gate – replace old rect at y=556 with just the badge ref (badge does the drawing)
html = html.replace(
    '<rect x="330" y="556" width="100" height="32" rx="4" fill="var(--warning-color)" opacity="0.95" filter="url(#glow-orange)"/>',
    ''
)
html = html.replace(
    '<text x="380" y="569" fill="#0d1117" font-size="9" font-weight="bold" text-anchor="middle">SCOPE GATE</text>',
    ''
)
html = html.replace(
    '<text x="380" y="581" fill="#0d1117" font-size="7" text-anchor="middle">eBPF LSM hook</text>',
    ''
)
# Fix slide 5 crash arrows (were at 380,555 → now 380,562)
html = html.replace(
    '<line x1="380" y1="555" x2="363" y2="537" stroke="var(--attack-color)" stroke-width="3" class="pulse-block"/>',
    '<line x1="380" y1="562" x2="363" y2="544" stroke="var(--attack-color)" stroke-width="3" class="pulse-block"/>'
)
html = html.replace(
    '<line x1="380" y1="555" x2="397" y2="537" stroke="var(--attack-color)" stroke-width="3" class="pulse-block"/>',
    '<line x1="380" y1="562" x2="397" y2="544" stroke="var(--attack-color)" stroke-width="3" class="pulse-block"/>'
)
html = html.replace(
    '<text x="410" y="533" fill="var(--attack-color)" font-size="10" font-weight="bold">DENIED ✕</text>',
    '<text x="415" y="540" fill="var(--attack-color)" font-size="10" font-weight="bold">DENIED ✕</text>'
)
# Context map line starts from badge bottom (y=606)
html = html.replace(
    '<line x1="380" y1="588" x2="380" y2="660" stroke="var(--warning-color)"',
    '<line x1="380" y1="606" x2="380" y2="660" stroke="var(--warning-color)"'
)
# Valid path through scope gate
html = html.replace(
    '<polyline points="380,588 380,620" fill="none" stroke="var(--thread-color)"',
    '<polyline points="380,606 380,650" fill="none" stroke="var(--thread-color)"'
)
html = html.replace(
    '<text x="393" y="610" fill="var(--thread-color)" font-size="8">✓ valid: /public/doc.pdf</text>',
    '<text x="393" y="638" fill="var(--thread-color)" font-size="8">✓ valid: /public/doc.pdf</text>'
)

# ── 7. UPDATE JS allLayers and show arrays ──────────────────────────────────
html = html.replace(
    "'bobWhitelist', 'execveDenied', \n    'redAttackSlide2', 'startupBulkhead', 'shutdownGate', 'bobPhaseGuard', 'redAttackSlide3', \n    'stacktraceEnforcement', 'stacktraceTunnel', 'threadScope'",
    "'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'badgeReadScope',\n    'redAttackSlide2', 'startupBulkhead', 'shutdownGate', 'redAttackSlide3', \n    'stacktraceEnforcement', 'threadScope'"
)

# Fix slide show arrays
# Slide 2
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'bobWhitelist', 'execveDenied', 'redAttackSlide2', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'badgeExecveWhitelist', 'redAttackSlide2', 'syscalls', 'legend']"
)
# Slide 3
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'bobWhitelist', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'bobPhaseGuard', 'redAttackSlide3', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'blueThread', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'startupBulkhead', 'shutdownGate', 'redAttackSlide3', 'syscalls', 'legend']"
)
# Slide 4
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'bobWhitelist', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'bobPhaseGuard', 'stacktraceEnforcement', 'stacktraceTunnel', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadStack', 'startupBulkhead', 'shutdownGate', 'stacktraceEnforcement', 'syscalls', 'legend']"
)
# Slide 5
html = html.replace(
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'bobWhitelist', 'execveDenied', 'startupBulkhead', 'shutdownGate', 'bobPhaseGuard', 'threadScope', 'syscalls', 'legend']",
    "show: ['startupZone', 'shutdownZone', 'entryPoint', 'mazeWallsExtra', 'badgeExecveWhitelist', 'badgeMprotectPhase', 'badgeReadScope', 'startupBulkhead', 'shutdownGate', 'threadScope', 'syscalls', 'legend']"
)

with open('presentation/presentation.html', 'w') as f:
    f.write(html)

print("Done. Check presentation/presentation.html")
